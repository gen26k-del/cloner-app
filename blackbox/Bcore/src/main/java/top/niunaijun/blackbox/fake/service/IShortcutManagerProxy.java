package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;

import java.lang.reflect.Method;
import java.util.ArrayList;

import black.android.content.pm.BRIShortcutServiceStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.fake.service.base.PkgMethodProxy;
import top.niunaijun.blackbox.utils.MethodParameterUtils;
import top.niunaijun.blackbox.utils.compat.ParceledListSliceCompat;


public class IShortcutManagerProxy extends BinderInvocationStub {

    public IShortcutManagerProxy() {
        super(BRServiceManager.get().getService(Context.SHORTCUT_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRIShortcutServiceStub.get().asInterface(BRServiceManager.get().getService(Context.SHORTCUT_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.SHORTCUT_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    protected void onBindMethod() {
        super.onBindMethod();
        addMethodHook(new PkgMethodProxy("getShortcuts"));
        addMethodHook(new PkgMethodProxy("disableShortcuts"));
        addMethodHook(new PkgMethodProxy("enableShortcuts"));
        addMethodHook(new PkgMethodProxy("getRemainingCallCount"));
        addMethodHook(new PkgMethodProxy("getRateLimitResetTime"));
        addMethodHook(new PkgMethodProxy("getIconMaxDimensions"));
        addMethodHook(new PkgMethodProxy("getMaxShortcutCountPerActivity"));
        addMethodHook(new PkgMethodProxy("reportShortcutUsed"));
        addMethodHook(new PkgMethodProxy("onApplicationActive"));
        addMethodHook(new PkgMethodProxy("hasShortcutHostPermission"));
        addMethodHook(new PkgMethodProxy("removeAllDynamicShortcuts"));
        addMethodHook(new PkgMethodProxy("removeDynamicShortcuts"));
        addMethodHook(new PkgMethodProxy("removeLongLivedShortcuts"));
        addMethodHook(new PkgMethodProxy("getManifestShortcuts"){
            @Override
            protected Object hook(Object who, Method method, Object[] args) throws Throwable {
                return ParceledListSliceCompat.create(new ArrayList<ShortcutInfo>());
            }
        });
    }

    @ProxyMethod("requestPinShortcut")
    public static class RequestPinShortcut extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // v1.0.163 (popup fix): the framework's
            // ShortcutManager.requestPinShortcut does NOT finish on this
            // AIDL call's boolean return. On Android 13/14 it parks the
            // calling thread on a CompletableFuture that only the
            // service's IShortcutCallback.onResult(...) completes
            // (device stack, .162 report: requestPinShortcut:604 ->
            // getFutureOrThrow:799 -> CompletableFuture.get -> park,
            // blocked 4011-7014ms from a katana tap, real ANR in :p1).
            // The old hook swallowed the request and returned true
            // without ever invoking the callback, so no one completed
            // the future and the guest main thread parked indefinitely
            // -> the "isn't responding" dialog. Pinning is not performed
            // in the virtual environment, so complete the callback
            // immediately with false: the request fails fast instead of
            // hanging. The callback is found by type name (its position
            // in args is an AIDL detail), invoked on the calling thread
            // before the framework reaches future.get(), so the get()
            // returns at once.
            Object callback = null;
            if (args != null) {
                for (Object arg : args) {
                    if (arg != null && arg.getClass().getName().contains("IShortcutCallback")) {
                        callback = arg;
                        break;
                    }
                }
            }
            boolean completed = false;
            if (callback != null) {
                try {
                    Method onResult;
                    try {
                        onResult = Class.forName("android.content.pm.IShortcutCallback")
                                .getMethod("onResult", boolean.class);
                    } catch (Throwable t) {
                        onResult = callback.getClass().getMethod("onResult", boolean.class);
                    }
                    try {
                        onResult.invoke(callback, false);
                    } catch (IllegalAccessException e) {
                        onResult.setAccessible(true);
                        onResult.invoke(callback, false);
                    }
                    completed = true;
                } catch (Throwable ignored) {
                }
            }
            try {
                BlackBoxCore.traceInitPublic(BlackBoxCore.getContext(),
                        "PinShortcut: pkg=" + BActivityThread.getAppPackageName()
                                + " callback=" + (callback != null)
                                + " completed=" + completed + " result=false");
            } catch (Throwable ignored) {
            }
            return true;
        }
    }

    @ProxyMethod("setDynamicShortcuts")
    public static class SetDynamicShortcuts extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return true;
        }
    }

    @ProxyMethod("addDynamicShortcuts")
    public static class AddDynamicShortcuts extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return true;
        }
    }

    @ProxyMethod("createShortcutResultIntent")
    public static class CreateShortcutResultIntent extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return new Intent();
        }
    }

    @ProxyMethod("pushDynamicShortcut")
    public static class pushDynamicShortcut extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        MethodParameterUtils.replaceAllAppPkg(args);
        return super.invoke(proxy, method, args);
    }
}
