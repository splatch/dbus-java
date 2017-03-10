/*
   D-Bus Java Implementation
   Copyright (c) 2005-2006 Matthew Johnson

   This program is free software; you can redistribute it and/or modify it
   under the terms of either the GNU Lesser General Public License Version 2 or the
   Academic Free Licence Version 2.1.

   Full licence texts are included in the COPYING file with this program.
*/
package org.freedesktop.dbus;

import static org.freedesktop.dbus.Gettext.t;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.text.MessageFormat;
import java.util.Arrays;

import org.freedesktop.DBus;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.exceptions.DBusExecutionException;
import org.freedesktop.dbus.exceptions.NotConnected;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class RemoteInvocationHandler implements InvocationHandler {
    public static final int CALL_TYPE_SYNC     = 0;
    public static final int CALL_TYPE_ASYNC    = 1;
    public static final int CALL_TYPE_CALLBACK = 2;

    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteInvocationHandler.class);



    public static Object convertRV(String sig, Object[] rp, Method m, AbstractConnection conn) throws DBusException {
        Class<? extends Object> c = m.getReturnType();

        if (null == rp) {
            if (null == c || Void.TYPE.equals(c)) {
                return null;
            } else {
                throw new DBusExecutionException(t("Wrong return type (got void, expected a value)"));
            }
        } else {
            try {
                LOGGER.trace("Converting return parameters from " + Arrays.deepToString(rp) + " to type " + m.getGenericReturnType());
                rp = Marshalling.deSerializeParameters(rp, new Type[] {
                        m.getGenericReturnType()
                }, conn);
            } catch (Exception e) {
                if (AbstractConnection.EXCEPTION_DEBUG) {
                    LOGGER.error("", e);
                }
                throw new DBusExecutionException(MessageFormat.format(t("Wrong return type (failed to de-serialize correct types: {0} )"), new Object[] {
                        e.getMessage()
                }));
            }
        }

        switch (rp.length) {
        case 0:
            if (null == c || Void.TYPE.equals(c)) {
                return null;
            } else {
                throw new DBusExecutionException(t("Wrong return type (got void, expected a value)"));
            }
        case 1:
            return rp[0];
        default:

            // check we are meant to return multiple values
            if (!Tuple.class.isAssignableFrom(c)) {
                throw new DBusExecutionException(t("Wrong return type (not expecting Tuple)"));
            }

            Constructor<? extends Object> cons = c.getConstructors()[0];
            try {
                return cons.newInstance(rp);
            } catch (Exception e) {
                if (AbstractConnection.EXCEPTION_DEBUG) {
                    LOGGER.error("", e);
                }
                throw new DBusException(e.getMessage());
            }
        }
    }

    public static Object executeRemoteMethod(RemoteObject ro, Method m, AbstractConnection conn, int syncmethod, CallbackHandler<?> callback, Object... args) throws DBusExecutionException {
        Type[] ts = m.getGenericParameterTypes();
        String sig = null;
        if (ts.length > 0) {
            try {
                sig = Marshalling.getDBusType(ts);
                args = Marshalling.convertParameters(args, ts, conn);
            } catch (DBusException exDbe) {
                throw new DBusExecutionException(t("Failed to construct D-Bus type: ") + exDbe.getMessage());
            }
        }
        MethodCall call;
        byte flags = 0;
        if (!ro.autostart) {
            flags |= Message.Flags.NO_AUTO_START;
        }
        if (syncmethod == CALL_TYPE_ASYNC) {
            flags |= Message.Flags.ASYNC;
        }
        if (m.isAnnotationPresent(DBus.Method.NoReply.class)) {
            flags |= Message.Flags.NO_REPLY_EXPECTED;
        }
        try {
            String name;
            if (m.isAnnotationPresent(DBusMemberName.class)) {
                name = m.getAnnotation(DBusMemberName.class).value();
            } else {
                name = m.getName();
            }
            if (null == ro.iface) {
                call = new MethodCall(ro.busname, ro.objectpath, null, name, flags, sig, args);
            } else {
                if (null != ro.iface.getAnnotation(DBusInterfaceName.class)) {
                    call = new MethodCall(ro.busname, ro.objectpath, ro.iface.getAnnotation(DBusInterfaceName.class).value(), name, flags, sig, args);
                } else {
                    call = new MethodCall(ro.busname, ro.objectpath, AbstractConnection.DOLLAR_PATTERN.matcher(ro.iface.getName()).replaceAll("."), name, flags, sig, args);
                }
            }
        } catch (DBusException dbe) {
            if (AbstractConnection.EXCEPTION_DEBUG) {
                LOGGER.error("", dbe);
            }
            throw new DBusExecutionException(t("Failed to construct outgoing method call: ") + dbe.getMessage());
        }
        if (null == conn.outgoing) {
            throw new NotConnected(t("Not Connected"));
        }

        switch (syncmethod) {
        case CALL_TYPE_ASYNC:
            conn.queueOutgoing(call);
            return new DBusAsyncReply<>(call, m, conn);
        case CALL_TYPE_CALLBACK:
            synchronized (conn.pendingCallbacks) {
                LOGGER.trace("Queueing Callback " + callback + " for " + call);
                conn.pendingCallbacks.put(call, callback);
                conn.pendingCallbackReplys.put(call, new DBusAsyncReply<>(call, m, conn));
            }
            conn.queueOutgoing(call);
            return null;
        case CALL_TYPE_SYNC:
            conn.queueOutgoing(call);
            break;
        }

        // get reply
        if (m.isAnnotationPresent(DBus.Method.NoReply.class)) {
            return null;
        }

        Message reply = call.getReply();
        if (null == reply) {
            throw new DBus.Error.NoReply(t("No reply within specified time"));
        }

        if (reply instanceof Error) {
            ((Error) reply).throwException();
        }

        try {
            return convertRV(reply.getSig(), reply.getParameters(), m, conn);
        } catch (DBusException e) {
            if (AbstractConnection.EXCEPTION_DEBUG) {
                LOGGER.error("", e);
            }
            throw new DBusExecutionException(e.getMessage());
        }
    }

    // CHECKSTYLE:OFF
    AbstractConnection conn;
    RemoteObject       remote;
    // CHECKSTYLE:ON

    RemoteInvocationHandler(AbstractConnection _conn, RemoteObject _remote) {
        this.remote = _remote;
        this.conn = _conn;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getName().equals("isRemote")) {
            return true;
        } else if (method.getName().equals("getObjectPath")) {
            return remote.objectpath;
        } else if (method.getName().equals("clone")) {
            return null;
        } else if (method.getName().equals("equals")) {
            try {
                if (1 == args.length) {
                    return new Boolean(remote.equals(((RemoteInvocationHandler) Proxy.getInvocationHandler(args[0])).remote));
                }
            } catch (IllegalArgumentException exIa) {
                return Boolean.FALSE;
            }
        } else if (method.getName().equals("finalize")) {
            return null;
        } else if (method.getName().equals("getClass")) {
            return DBusInterface.class;
        } else if (method.getName().equals("hashCode")) {
            return remote.hashCode();
        } else if (method.getName().equals("notify")) {
            remote.notify();
            return null;
        } else if (method.getName().equals("notifyAll")) {
            remote.notifyAll();
            return null;
        } else if (method.getName().equals("wait")) {
            if (0 == args.length) {
                remote.wait();
            } else if (1 == args.length && args[0] instanceof Long) {
                remote.wait((Long) args[0]);
            } else if (2 == args.length && args[0] instanceof Long && args[1] instanceof Integer) {
                remote.wait((Long) args[0], (Integer) args[1]);
            }
            if (args.length <= 2) {
                return null;
            }
        } else if (method.getName().equals("toString")) {
            return remote.toString();
        }

        return executeRemoteMethod(remote, method, conn, CALL_TYPE_SYNC, null, args);
    }
}
