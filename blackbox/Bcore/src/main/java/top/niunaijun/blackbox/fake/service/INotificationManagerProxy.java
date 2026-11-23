package top.niunaijun.blackbox.fake.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.content.Context;
import android.os.Build;

import androidx.annotation.RequiresApi;

import java.lang.reflect.Method;
import java.util.List;

import black.android.app.BRNotificationManager;
import black.android.content.pm.BRParceledListSlice;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.frameworks.BNotificationManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.MethodParameterUtils;
import top.niunaijun.blackbox.utils.compat.BuildCompat;
import top.niunaijun.blackbox.utils.compat.ParceledListSliceCompat;


public class INotificationManagerProxy extends BinderInvocationStub {
    public static final String TAG = "INotificationManagerProxy";

    public INotificationManagerProxy() {
        super(BRNotificationManager.get().getService().asBinder());
    }

    @Override
    protected Object getWho() {
        return BRNotificationManager.get().getService();
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        BRNotificationManager.get()._set_sService(getProxyInvocation());
        replaceSystemService(Context.NOTIFICATION_SERVICE);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

        MethodParameterUtils.replaceAllAppPkg(args);
        return super.invoke(proxy, method, args);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    // v1.0.150: নোটিফিকেশন ট্রেস (শুধু প্রমাণ সংগ্রহ — আচরণে কোনো
    // বদল নয়)। মালিক জানিয়েছেন (2026-11-23) Facebook clone-এ
    // নোটিফিকেশন একদমই আসে না। কোন clone কখন notify()/cancel()
    // ডাকল তা init trace-এ NotifyPost:/NotifyCancel: লাইন হিসেবে
    // লেখা থাকে; স্মার্ট রিপোর্টের আলাদা অংশে গোনা হয়। পরের
    // রিপোর্টেই বোঝা যাবে katana আদৌ post করে কিনা (কোনো লাইন না
    // থাকলে Facebook clone-এর ভেতর থেকে post-ই হয় না), নাকি post
    // হয়েও surface হয় না। প্যাকেজ নাম নেওয়া হয় guest-এর নিজের
    // bound package থেকে — invoke()-তে args-এর প্যাকেজ আগেই host
    // প্যাকেজে বদলে যায় বলে args থেকে নিলে ভুল নাম আসত।
    private static void traceNotify(String action, int id, String tag) {
        try {
            String pkg = BActivityThread.getAppPackageName();
            if (pkg == null) return;
            BlackBoxCore.traceInitPublic(BlackBoxCore.getContext(),
                    action + ": pkg=" + pkg + " user=" + BActivityThread.getUserId()
                            + " id=" + id + " tag=" + tag);
        } catch (Throwable ignored) { }
    }

    @ProxyMethod("getNotificationChannel")
    public static class GetNotificationChannel extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            NotificationChannel notificationChannel = BNotificationManager.get().getNotificationChannel((String) args[args.length - 1]);
            return notificationChannel;
        }
    }

    @ProxyMethod("getNotificationChannels")
    public static class GetNotificationChannels extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            List<NotificationChannel> notificationChannels = BNotificationManager.get().getNotificationChannels(BActivityThread.getAppPackageName());
            return ParceledListSliceCompat.create(notificationChannels);
        }
    }

    @ProxyMethod("cancelNotificationWithTag")
    public static class CancelNotificationWithTag extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String tag = (String) args[getTagIndex()];
            int id = (int) args[getIdIndex()];
            BNotificationManager.get().cancelNotificationWithTag(id, tag);
            traceNotify("NotifyCancel", id, tag);
            return 0;
        }

        public int getTagIndex() {
            if (BuildCompat.isR()) {
                return 2;
            }
            return 1;
        }

        public int getIdIndex() {
            return getTagIndex() + 1;
        }
    }


    @ProxyMethod("enqueueNotificationWithTag")
    public static class EnqueueNotificationWithTag extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String tag = (String) args[getTagIndex()];
            int id = (int) args[getIdIndex()];
            Notification notification = MethodParameterUtils.getFirstParam(args, Notification.class);
            BNotificationManager.get().enqueueNotificationWithTag(id, tag, notification);
            traceNotify("NotifyPost", id, tag);
            return 0;
        }

        public int getTagIndex() {
            return 2;
        }

        public int getIdIndex() {
            return getTagIndex() + 1;
        }
    }

    @ProxyMethod("createNotificationChannels")
    @RequiresApi(api = Build.VERSION_CODES.O)
    public static class CreateNotificationChannels extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            List<?> list = BRParceledListSlice.get(args[1]).getList();
            if (list == null)
                return 0;
            StringBuilder channelIds = new StringBuilder();
            for (Object o : list) {
                NotificationChannel channel = (NotificationChannel) o;
                BNotificationManager.get().createNotificationChannel(channel);
                if (channelIds.length() > 0) channelIds.append(",");
                channelIds.append(channel.getId());
            }
            // v1.0.151: পুশ-তদন্তের ট্রেস (আচরণে কোনো বদল নয়)।
            // guest তার notification channel তৈরি করলেই NotifyChannel:
            // লাইন থাকে — katana-র এমন লাইন না থাকলে ওর notification
            // উপব্যবস্থা clone-এর ভেতরে কখনো initialize-ই হয়নি (পুশ
            // registration ব্যর্থ হওয়ার পার্শ্ব-লক্ষণ হতে পারে)।
            try {
                String pkg = BActivityThread.getAppPackageName();
                if (pkg != null) {
                    BlackBoxCore.traceInitPublic(BlackBoxCore.getContext(),
                            "NotifyChannel: pkg=" + pkg + " user=" + BActivityThread.getUserId()
                                    + " channels=" + channelIds);
                }
            } catch (Throwable ignored) { }
            return 0;
        }
    }

    @ProxyMethod("deleteNotificationChannel")
    public static class DeleteNotificationChannel extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BNotificationManager.get().deleteNotificationChannel((String) args[1]);
            return 0;
        }
    }

    @ProxyMethod("createNotificationChannelGroups")
    @RequiresApi(api = Build.VERSION_CODES.O)
    public static class CreateNotificationChannelGroups extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            List<?> list = BRParceledListSlice.get(args[1]).getList();
            for (Object o : list) {
                BNotificationManager.get().createNotificationChannelGroup((NotificationChannelGroup) o);
            }
            return 0;
        }
    }

    @ProxyMethod("deleteNotificationChannelGroup")
    public static class DeleteNotificationChannelGroup extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BNotificationManager.get().deleteNotificationChannelGroup((String) args[1]);
            return 0;
        }
    }

    @ProxyMethod("getNotificationChannelGroups")
    public static class GetNotificationChannelGroups extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            List<NotificationChannelGroup> notificationChannelGroups = BNotificationManager.get().getNotificationChannelGroups(BActivityThread.getAppPackageName());
            return ParceledListSliceCompat.create(notificationChannelGroups);
        }
    }
}
