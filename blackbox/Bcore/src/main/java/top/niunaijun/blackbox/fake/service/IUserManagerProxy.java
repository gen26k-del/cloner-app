package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.os.Bundle;

import java.lang.reflect.Method;
import java.util.ArrayList;

import black.android.content.pm.BRUserInfo;
import black.android.os.BRIUserManagerStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;


public class IUserManagerProxy extends BinderInvocationStub {
    public IUserManagerProxy() {
        super(BRServiceManager.get().getService(Context.USER_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRIUserManagerStub.get().asInterface(BRServiceManager.get().getService(Context.USER_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.USER_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("getApplicationRestrictions")
    public static class GetApplicationRestrictions extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            args[0] = BlackBoxCore.getHostPkg();
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getApplicationRestrictionsForUser")
    public static class GetApplicationRestrictionsForUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // Chrome UserManager.getApplicationRestrictions() ডাকে; আসল UserManager
            // "Only system may" SecurityException দেয়, কারণ ভার্চুয়াল প্রসেস
            // হোস্ট UID-তে চলে, com.android.chrome-এর মালিক নয়। খালি Bundle মানে
            // "কোনো restriction নেই" — ব্যক্তিগত ফোনে এটাই সঠিক। না হলে Chromium
            // JNI-তে SIGTRAP দিয়ে পুরো প্রসেস মেরে ফেলে।
            return new Bundle();
        }
    }

    @ProxyMethod("getProfileParent")
    public static class GetProfileParent extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Object blackBox = BRUserInfo.get()._new(BActivityThread.getUserId(), "BlackBox", BRUserInfo.get().FLAG_PRIMARY());
            return blackBox;
        }
    }

    @ProxyMethod("getUsers")
    public static class getUsers extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return new ArrayList<>();
        }
    }
}
