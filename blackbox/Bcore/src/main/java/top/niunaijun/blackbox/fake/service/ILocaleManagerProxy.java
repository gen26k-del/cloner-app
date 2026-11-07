package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.os.LocaleList;

import java.lang.reflect.Method;

import black.android.app.BRILocaleManagerStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;


public class ILocaleManagerProxy extends BinderInvocationStub {
    public ILocaleManagerProxy() {
        super(BRServiceManager.get().getService(Context.LOCALE_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRILocaleManagerStub.get().asInterface(BRServiceManager.get().getService(Context.LOCALE_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.LOCALE_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("getApplicationLocales")
    public static class GetApplicationLocales extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // Chrome LocaleManager.getApplicationLocales() ডাকে (idle handler থেকে);
            // আসল LocaleManager "Neither user nor current process has
            // android.permission.READ_APP_SPECIFIC_LOCALES" SecurityException দেয়,
            // কারণ ভার্চুয়াল প্রসেস হোস্ট UID-তে চলে, Chrome-এর মালিক নয়।
            // খালি LocaleList মানে "সিস্টেম ডিফল্ট লোকেল" — ব্যক্তিগত ফোনে সঠিক।
            return new LocaleList();
        }
    }
}
