package top.niunaijun.blackbox.fake.service;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;

import java.lang.reflect.Method;

import black.android.app.BRAppOpsManager;
import black.android.os.BRServiceManager;
import black.com.android.internal.app.BRIAppOpsServiceStub;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.MethodParameterUtils;
import top.niunaijun.blackbox.utils.Slog;


public class IAppOpsManagerProxy extends BinderInvocationStub {
    public IAppOpsManagerProxy() {
        super(BRServiceManager.get().getService(Context.APP_OPS_SERVICE));
    }

    @Override
    protected Object getWho() {
        IBinder call = BRServiceManager.get().getService(Context.APP_OPS_SERVICE);
        return BRIAppOpsServiceStub.get().asInterface(call);
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        if (BRAppOpsManager.get(null)._check_mService() != null) {
            AppOpsManager appOpsManager = (AppOpsManager) BlackBoxCore.getContext().getSystemService(Context.APP_OPS_SERVICE);
            try {
                BRAppOpsManager.get(appOpsManager)._set_mService(getProxyInvocation());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        replaceSystemService(Context.APP_OPS_SERVICE);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String methodName = method.getName();
        
        
        
        if (methodName.startsWith("check") ||
            methodName.startsWith("note") ||
            methodName.startsWith("start")) {
            Slog.d(TAG, "AppOps invoke: Bypassing system for " + methodName + ", allowing operation");
            // v1.0.135: API 30+ noteOperation/startOp/noteProxyOperation
            // return SyncNotedAppOp, not int. Returning MODE_ALLOWED
            // crashed callers with ClassCastException (WhatsApp,
            // 2026-11-15 19:17:56). Build the object by reflection;
            // on failure fall through to the real service below.
            boolean needsNotedOp = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    && (methodName.equals("noteOperation") || methodName.equals("startOp")
                    || methodName.equals("noteProxyOperation"));
            if (!needsNotedOp) {
                return AppOpsManager.MODE_ALLOWED;
            }
            Object noted = methodName.equals("noteProxyOperation")
                    ? notedOpFromAttributionSourceArg(args) : notedOpFromArgs(args);
            if (noted != null) {
                return noted;
            }
        }
        
        
        if (methodName.startsWith("finish")) {
            Slog.d(TAG, "AppOps invoke: Bypassing system for " + methodName);
            return null;
        }
        
        
        try {
            MethodParameterUtils.replaceFirstAppPkg(args);
            MethodParameterUtils.replaceLastUid(args);
            return super.invoke(proxy, method, args);
        } catch (SecurityException e) {
            Slog.w(TAG, "AppOps invoke: SecurityException caught for " + methodName + ", allowing operation", e);
            return fallbackResult(method);
        } catch (Exception e) {
            Slog.e(TAG, "AppOps invoke: Error in method " + methodName, e);
            return fallbackResult(method);
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    // v1.0.135 helpers: SyncNotedAppOp is not in the public stubs,
    // so everything below is reflection. Any failure returns null and
    // the caller falls back to the real service.
    private static Object newSyncNotedAppOp(Object attributionSource) {
        try {
            Class<?> notedCls = Class.forName("android.app.SyncNotedAppOp");
            Class<?> attributionSourceCls = Class.forName("android.content.AttributionSource");
            java.lang.reflect.Constructor<?> ctor;
            try {
                ctor = notedCls.getConstructor(int.class, attributionSourceCls, String.class);
            } catch (NoSuchMethodException e) {
                ctor = notedCls.getDeclaredConstructor(int.class, attributionSourceCls, String.class);
                ctor.setAccessible(true);
            }
            return ctor.newInstance(AppOpsManager.MODE_ALLOWED, attributionSource, null);
        } catch (Throwable t) {
            Slog.d(TAG, "AppOps newSyncNotedAppOp failed: " + t);
            return null;
        }
    }

    private static Object newAttributionSource(int uid, String pkg, String tag) {
        try {
            Class<?> builderCls = Class.forName("android.content.AttributionSource$Builder");
            Object builder = builderCls.getConstructor(int.class).newInstance(uid);
            if (pkg != null) {
                builderCls.getMethod("setPackageName", String.class).invoke(builder, pkg);
            }
            if (tag != null) {
                builderCls.getMethod("setAttributionTag", String.class).invoke(builder, tag);
            }
            return builderCls.getMethod("build").invoke(builder);
        } catch (Throwable t) {
            Slog.d(TAG, "AppOps newAttributionSource failed: " + t);
            return null;
        }
    }

    // noteOperation/startOp args: (code, uid, packageName, attributionTag, ...)
    private static Object notedOpFromArgs(Object[] args) {
        int uid = Process.myUid();
        String pkg = null;
        String tag = null;
        if (args != null) {
            if (args.length > 1 && args[1] instanceof Integer) uid = (Integer) args[1];
            if (args.length > 2 && args[2] instanceof String) pkg = (String) args[2];
            if (args.length > 3 && args[3] instanceof String) tag = (String) args[3];
        }
        return newSyncNotedAppOp(newAttributionSource(uid, pkg, tag));
    }

    // noteProxyOperation args: (code, AttributionSource, ...)
    private static Object notedOpFromAttributionSourceArg(Object[] args) {
        if (args != null && args.length > 1 && args[1] != null
                && args[1].getClass().getName().equals("android.content.AttributionSource")) {
            return newSyncNotedAppOp(args[1]);
        }
        return null;
    }

    private static Object fallbackResult(Method method) {
        Class<?> returnType = method.getReturnType();
        if (returnType == int.class || returnType == Integer.class) {
            return AppOpsManager.MODE_ALLOWED;
        }
        return null;
    }

    @ProxyMethod("noteProxyOperation")
    public static class NoteProxyOperation extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Object noted = notedOpFromAttributionSourceArg(args);
                if (noted != null) return noted;
                return method.invoke(who, args);
            }
            return AppOpsManager.MODE_ALLOWED;
        }
    }

    @ProxyMethod("checkPackage")
    public static class CheckPackage extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            return AppOpsManager.MODE_ALLOWED;
        }
    }

    @ProxyMethod("checkOperation")
    public static class CheckOperation extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            
            Slog.d(TAG, "AppOps CheckOperation: Bypassing system check, allowing operation");
            return AppOpsManager.MODE_ALLOWED;
        }
    }

    
    @ProxyMethod("checkOperationForDevice")
    public static class CheckOperationForDevice extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            Slog.d(TAG, "AppOps CheckOperationForDevice: Bypassing system check, allowing operation");
            return AppOpsManager.MODE_ALLOWED;
        }
    }

    @ProxyMethod("noteOperation")
    public static class NoteOperation extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AppOps NoteOperation: Bypassing system check, allowing operation");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Object noted = notedOpFromArgs(args);
                if (noted != null) return noted;
                return method.invoke(who, args);
            }
            return AppOpsManager.MODE_ALLOWED;
        }
    }

    @ProxyMethod("checkOpNoThrow")
    public static class CheckOpNoThrow extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            Slog.d(TAG, "AppOps CheckOpNoThrow: Bypassing system check, allowing operation");
            return AppOpsManager.MODE_ALLOWED;
        }
    }

    
    @ProxyMethod("startOp")
    public static class StartOp extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "AppOps StartOp: Bypassing system check, allowing operation");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Object noted = notedOpFromArgs(args);
                if (noted != null) return noted;
                return method.invoke(who, args);
            }
            return AppOpsManager.MODE_ALLOWED;
        }
    }

    @ProxyMethod("startOpNoThrow")
    public static class StartOpNoThrow extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            Slog.d(TAG, "AppOps StartOpNoThrow: Bypassing system check, allowing operation");
            return AppOpsManager.MODE_ALLOWED;
        }
    }

    
    @ProxyMethod("finishOp")
    public static class FinishOp extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                int op = (int) args[0];
                String name = getOpPublicName(op);
                if (name != null && isMediaStorageOrAudioOp(name)) {
                    Slog.d(TAG, "AppOps FinishOp: Finishing operation: " + name);
                }
            } catch (Throwable ignored) {
            }
            return null;
        }
    }

    
    @ProxyMethod("noteOp")
    public static class NoteOp extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                int op = (int) args[0];
                String name = getOpPublicName(op);
                if (name != null && (name.contains("RECORD_AUDIO") || name.contains("AUDIO") || name.contains("MICROPHONE"))) {
                    Slog.d(TAG, "AppOps NoteOp: Allowing RECORD_AUDIO operation: " + name);
                    return AppOpsManager.MODE_ALLOWED;
                }
            } catch (Throwable ignored) {
            }
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("noteOpNoThrow")
    public static class NoteOpNoThrow extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                int op = (int) args[0];
                String name = getOpPublicName(op);
                if (name != null && (name.contains("RECORD_AUDIO") || name.contains("AUDIO") || name.contains("MICROPHONE"))) {
                    Slog.d(TAG, "AppOps NoteOpNoThrow: Allowing RECORD_AUDIO operation: " + name);
                    return AppOpsManager.MODE_ALLOWED;
                }
            } catch (Throwable ignored) {
            }
            return method.invoke(who, args);
        }
    }

    private static boolean isMediaStorageOrAudioOp(String opPublicNameOrStr) {
        if (opPublicNameOrStr == null) return false;
        
        String n = opPublicNameOrStr.toUpperCase();
        return n.contains("READ_MEDIA")
                || n.contains("READ_EXTERNAL_STORAGE")
                || n.contains("RECORD_AUDIO")
                || n.contains("CAPTURE_AUDIO_OUTPUT")
                || n.contains("MODIFY_AUDIO_SETTINGS")
                || n.contains("AUDIO")
                || n.contains("MICROPHONE")
                || n.contains("FOREGROUND_SERVICE")
                || n.contains("SYSTEM_ALERT_WINDOW")
                || n.contains("WRITE_SETTINGS")
                || n.contains("ACCESS_FINE_LOCATION")
                || n.contains("ACCESS_COARSE_LOCATION")
                || n.contains("CAMERA")
                || n.contains("BODY_SENSORS")
                || n.contains("BLUETOOTH_SCAN")
                || n.contains("BLUETOOTH_CONNECT")
                || n.contains("BLUETOOTH_ADVERTISE")
                || n.contains("NEARBY_WIFI_DEVICES")
                || n.contains("POST_NOTIFICATIONS");
    }

    private static String getOpPublicName(int op) {
        try {
            
            java.lang.reflect.Method m = AppOpsManager.class.getMethod("opToPublicName", int.class);
            Object name = m.invoke(null, op);
            return name != null ? name.toString() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
