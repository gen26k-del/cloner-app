package top.niunaijun.blackbox.fake.service;

import android.content.Context;

import java.lang.reflect.Method;
import java.util.ArrayList;

import black.android.app.BRIRoleManagerStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;


public class IRoleManagerProxy extends BinderInvocationStub {
    public IRoleManagerProxy() {
        super(BRServiceManager.get().getService(Context.ROLE_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRIRoleManagerStub.get().asInterface(BRServiceManager.get().getService(Context.ROLE_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.ROLE_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("isRoleHeldAsUser")
    public static class IsRoleHeldAsUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // Chrome চালু হওয়ার সময় RoleManager.isRoleHeld(ROLE_BROWSER) ডাকে
            // (ডিফল্ট ব্রাউজার কিনা যাচাই); আসল RoleService AppOpsManager.
            // checkPackage-এ RemoteException দেয়, কারণ ভার্চুয়াল প্রসেস
            // হোস্ট UID-তে চলে, Chrome-এর মালিক নয় — আর Chrome FATAL ক্র্যাশ করে।
            // false মানে "ডিফল্ট ব্রাউজার নয়" — ভার্চুয়ালে নিরাপদ, Chrome চলতে থাকে।
            return false;
        }
    }

    @ProxyMethod("isRoleHeld")
    public static class IsRoleHeld extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return false;
        }
    }

    @ProxyMethod("getHeldRoles")
    public static class GetHeldRoles extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return new ArrayList<String>();
        }
    }

    @ProxyMethod("getRoleHoldersAsUser")
    public static class GetRoleHoldersAsUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return new ArrayList<String>();
        }
    }
}
