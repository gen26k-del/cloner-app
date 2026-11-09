package top.niunaijun.blackbox.fake.delegate;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.util.Log;

import java.lang.reflect.Field;

import black.android.app.BRActivity;
import black.android.app.BRActivityThread;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.HookManager;
import top.niunaijun.blackbox.fake.hook.IInjectHook;
import top.niunaijun.blackbox.fake.service.HCallbackProxy;
import top.niunaijun.blackbox.fake.service.IActivityClientProxy;
import top.niunaijun.blackbox.utils.HackAppUtils;
import top.niunaijun.blackbox.utils.compat.ActivityCompat;
import top.niunaijun.blackbox.utils.compat.ActivityManagerCompat;
import top.niunaijun.blackbox.utils.compat.ContextCompat;

public final class AppInstrumentation extends BaseInstrumentationDelegate implements IInjectHook {

    private static final String TAG = AppInstrumentation.class.getSimpleName();

    private static AppInstrumentation sAppInstrumentation;

    public static AppInstrumentation get() {
        if (sAppInstrumentation == null) {
            synchronized (AppInstrumentation.class) {
                if (sAppInstrumentation == null) {
                    sAppInstrumentation = new AppInstrumentation();
                }
            }
        }
        return sAppInstrumentation;
    }

    public AppInstrumentation() {
    }

    @Override
    public void injectHook() {
        try {
            Instrumentation mInstrumentation = getCurrInstrumentation();
            if (mInstrumentation == this || checkInstrumentation(mInstrumentation))
                return;
            mBaseInstrumentation = (Instrumentation) mInstrumentation;
            BRActivityThread.get(BlackBoxCore.mainThread())._set_mInstrumentation(this);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Instrumentation getCurrInstrumentation() {
        Object currentActivityThread = BlackBoxCore.mainThread();
        return BRActivityThread.get(currentActivityThread).mInstrumentation();
    }

    @Override
    public boolean isBadEnv() {
        return !checkInstrumentation(getCurrInstrumentation());
    }

    private boolean checkInstrumentation(Instrumentation instrumentation) {
        if (instrumentation instanceof AppInstrumentation) {
            return true;
        }
        Class<?> clazz = instrumentation.getClass();
        if (Instrumentation.class.equals(clazz)) {
            return false;
        }
        do {
            assert clazz != null;
            Field[] fields = clazz.getDeclaredFields();
            for (Field field : fields) {
                if (Instrumentation.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    try {
                        Object obj = field.get(instrumentation);
                        if ((obj instanceof AppInstrumentation)) {
                            return true;
                        }
                    } catch (Exception e) {
                        return false;
                    }
                }
            }
            clazz = clazz.getSuperclass();
        } while (!Instrumentation.class.equals(clazz));
        return false;
    }

    private void checkHCallback() {
        HookManager.get().checkEnv(HCallbackProxy.class);
    }

    private void checkActivity(Activity activity) {
        Log.d(TAG, "callActivityOnCreate: " + activity.getClass().getName());
        HackAppUtils.enableQQLogOutput(activity.getPackageName(), activity.getClassLoader());
        checkHCallback();
        HookManager.get().checkEnv(IActivityClientProxy.class);
        ActivityInfo info = BRActivity.get(activity).mActivityInfo();
        ContextCompat.fix(activity);
        ActivityCompat.fix(activity);
        if (info.theme != 0) {
            activity.getTheme().applyStyle(info.theme, true);
        }
        ActivityManagerCompat.setActivityOrientation(activity, info.screenOrientation);
    }

    @Override
    public Application newApplication(ClassLoader cl, String className, Context context) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        ContextCompat.fix(context);

        return super.newApplication(cl, className, context);
    }

    @Override
    public void callActivityOnCreate(Activity activity, Bundle icicle, PersistableBundle persistentState) {
        checkActivity(activity);
        super.callActivityOnCreate(activity, icicle, persistentState);
    }

    @Override
    public void callActivityOnCreate(Activity activity, Bundle icicle) {
        checkActivity(activity);
        try {
            top.niunaijun.blackbox.BlackBoxCore.traceInitPublic(
                top.niunaijun.blackbox.BlackBoxCore.getContext(),
                "AppInstrumentation.callActivityOnCreate start: " + activity.getClass().getName());
        } catch (Throwable ignored) { }
        super.callActivityOnCreate(activity, icicle);
        try {
            top.niunaijun.blackbox.BlackBoxCore.traceInitPublic(
                top.niunaijun.blackbox.BlackBoxCore.getContext(),
                "AppInstrumentation.callActivityOnCreate done: " + activity.getClass().getName());
        } catch (Throwable ignored) { }
    }

    @Override
    public void callActivityOnResume(Activity activity) {
        try {
            top.niunaijun.blackbox.BlackBoxCore.traceInitPublic(
                top.niunaijun.blackbox.BlackBoxCore.getContext(),
                "AppInstrumentation.callActivityOnResume: " + activity.getClass().getName());
        } catch (Throwable ignored) { }
        super.callActivityOnResume(activity);
    }

    @Override
    public void callApplicationOnCreate(Application app) {
        checkHCallback();
        try {
            super.callApplicationOnCreate(app);
        } catch (SecurityException e) {
            // কিছু অ্যাপ (যেমন DramaWave/freereels) onCreate-এ
            // LocaleManager.setApplicationLocales ডাকে, যা ভার্চুয়াল প্রসেসে
            // CHANGE_CONFIGURATION পারমিশন না থাকায় SecurityException দেয়।
            // লোকেল সেট না হলেও অ্যাপ চলতে পারে — তাই এই নির্দিষ্ট ব্যতিক্রমটি
            // গিলে ফেলো, অ্যাপকে "Unable to makeApplication" দিয়ে মরতে দিও না।
            String msg = e.getMessage();
            if (msg != null && (msg.contains("setApplicationLocales")
                    || msg.contains("CHANGE_CONFIGURATION"))) {
                Log.w("BlackBox", "Ignored setApplicationLocales SecurityException in "
                        + app.getClass().getName());
            } else {
                throw e;
            }
        }
        // v1.0.94: v1.0.93-তে নাম দিয়ে খুঁজতে গিয়ে ব্যর্থ হয়েছে —
        // `NoSuchMethodException: CommandLine.getInstance []` (ক্লাস পাওয়া
        // গেছে, কিন্তু R8 method-এর নাম বদলে দিয়েছে — v1.0.89-র field-এর
        // মতোই)। তাই এবার SIGNATURE দিয়ে খোঁজো: getInstance = static,
        // 0 arg, return type CommandLine; appendSwitch = 1 String arg,
        // void return — প্রতিটা candidate ডেকে hasSwitch (1 String arg,
        // boolean return) দিয়ে যাচাই করো, যেটা সত্যিই switch বসায় সেটাই
        // appendSwitch। GPU চ্যানেল টাইমআউট (35s, Android-only, no retry)
        // — ব্রাউজার GPU প্রসেসের Mojo উত্তরের অপেক্ষায় FATAL abort করছে
        // (18:17:22)। --disable-gpu Android-এ NOTREACHED। --in-process-gpu
        // একমাত্র switch যা timeout skip করে আর GPU-কে browser প্রসেসের
        // ভেতরে চালায়। Application.onCreate-এর পরে append করলে
        // GpuDataManager-এর সিদ্ধান্তের আগেই পৌঁছায় (গবেষণা)।
        try {
            String pkg = app.getPackageName();
            if (pkg != null && (pkg.contains("chrome") || pkg.contains("brave"))) {
                Class<?> cmdLineClass = null;
                ClassLoader[] cls = new ClassLoader[] {
                        app.getClassLoader(),
                        Thread.currentThread().getContextClassLoader(),
                        AppInstrumentation.class.getClassLoader()
                };
                for (ClassLoader cl : cls) {
                    if (cl == null) continue;
                    try {
                        cmdLineClass = Class.forName("org.chromium.base.CommandLine", false, cl);
                        break;
                    } catch (Throwable ignored) { }
                }
                if (cmdLineClass == null) {
                    top.niunaijun.blackbox.BlackBoxCore.traceInitPublic(
                            top.niunaijun.blackbox.BlackBoxCore.getContext(),
                            "v1.0.94 CommandLine class not found, in-process-gpu skipped");
                } else {
                    java.lang.reflect.Method getInstance = null;
                    java.lang.reflect.Method hasSwitch = null;
                    for (java.lang.reflect.Method m : cmdLineClass.getDeclaredMethods()) {
                        Class<?>[] params = m.getParameterTypes();
                        if (params.length == 0 && java.lang.reflect.Modifier.isStatic(m.getModifiers())
                                && m.getReturnType() == cmdLineClass) {
                            getInstance = m;
                        } else if (params.length == 1 && params[0] == String.class
                                && m.getReturnType() == boolean.class) {
                            hasSwitch = m;
                        }
                    }
                    if (getInstance == null) {
                        top.niunaijun.blackbox.BlackBoxCore.traceInitPublic(
                                top.niunaijun.blackbox.BlackBoxCore.getContext(),
                                "v1.0.94 CommandLine getInstance (by signature) not found");
                    } else {
                        getInstance.setAccessible(true);
                        Object cmdLine = getInstance.invoke(null);
                        if (cmdLine == null) {
                            top.niunaijun.blackbox.BlackBoxCore.traceInitPublic(
                                    top.niunaijun.blackbox.BlackBoxCore.getContext(),
                                    "v1.0.94 CommandLine instance is null");
                        } else {
                            boolean done = false;
                            String usedMethod = null;
                            for (java.lang.reflect.Method m : cmdLineClass.getDeclaredMethods()) {
                                Class<?>[] params = m.getParameterTypes();
                                if (params.length == 1 && params[0] == String.class
                                        && m.getReturnType() == void.class) {
                                    try {
                                        m.setAccessible(true);
                                        m.invoke(cmdLine, "in-process-gpu");
                                        if (hasSwitch != null) {
                                            hasSwitch.setAccessible(true);
                                            Boolean has = (Boolean) hasSwitch.invoke(cmdLine, "in-process-gpu");
                                            if (has != null && has) {
                                                done = true;
                                                usedMethod = m.getName();
                                                break;
                                            }
                                        } else {
                                            done = true;
                                            usedMethod = m.getName() + " (unverified)";
                                            break;
                                        }
                                    } catch (Throwable ignored) { }
                                }
                            }
                            top.niunaijun.blackbox.BlackBoxCore.traceInitPublic(
                                    top.niunaijun.blackbox.BlackBoxCore.getContext(),
                                    done ? "v1.0.94 in-process-gpu appended to browser commandLine via " + usedMethod
                                            : "v1.0.94 in-process-gpu append failed: no candidate worked");
                        }
                    }
                }
            }
        } catch (Throwable t) {
            try {
                top.niunaijun.blackbox.BlackBoxCore.traceInitPublic(
                        top.niunaijun.blackbox.BlackBoxCore.getContext(),
                        "v1.0.94 in-process-gpu append failed: " + t);
            } catch (Throwable ignored) { }
        }
    }

    public Activity newActivity(ClassLoader cl, String className, Intent intent) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        try {
            return super.newActivity(cl, className, intent);
        } catch (ClassNotFoundException e) {
            return mBaseInstrumentation.newActivity(cl, className, intent);
        }
    }
}
