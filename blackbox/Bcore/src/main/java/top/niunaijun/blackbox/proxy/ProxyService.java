package top.niunaijun.blackbox.proxy;

import android.app.Service;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.dispatcher.AppServiceDispatcher;
import top.niunaijun.blackbox.utils.compat.BuildCompat;


public class ProxyService extends Service {
    public static final String TAG = "StubService";

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        // v1.0.94: v1.0.92-র foreground promotion বন্ধ করা হলো — v1.0.93
        // টেস্টে প্রমাণ হয়েছে startForeground() নিজেই child প্রসেস মেরে
        // ফেলছে: `CannotPostForegroundServiceNotificationException: Bad
        // notification for startForeground` (privileged_process2 23:22:33,
        // sandboxed_process0 23:23:03 FATAL)। LMK অনুমান এমনিতেই দুর্বল
        // ছিল (GPU টাইমআউটই আসল খুনি), আর promotion-টা এখন ক্ষতি করছে।
        // showNotification() আর ডাকা হবে না।
        return AppServiceDispatcher.get().onBind(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        AppServiceDispatcher.get().onStartCommand(intent, flags, startId);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        AppServiceDispatcher.get().onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        AppServiceDispatcher.get().onConfigurationChanged(newConfig);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        AppServiceDispatcher.get().onLowMemory();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        AppServiceDispatcher.get().onTrimMemory(level);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        AppServiceDispatcher.get().onUnbind(intent);
        return false;
    }

    private static boolean sForegroundPromoted = false;

    private void showNotification(String serviceName) {
        // এক প্রসেসে একবারই — প্রতিটি bind-এ নোটিফিকেশন আবার পোস্ট কোরো না।
        if (sForegroundPromoted) return;
        sForegroundPromoted = true;
        try {
            String channelId = getPackageName() + ".blackbox_proxy";
            if (BuildCompat.isOreo()) {
                android.app.NotificationManager nm =
                        (android.app.NotificationManager) getSystemService(android.content.Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    android.app.NotificationChannel ch = new android.app.NotificationChannel(
                            channelId, "CloneMe — Chrome",
                            android.app.NotificationManager.IMPORTANCE_MIN);
                    ch.setDescription("ক্লোন Chrome-এর renderer সক্রিয় রাখে");
                    nm.createNotificationChannel(ch);
                }
            }
            NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), channelId)
                    .setContentTitle("CloneMe — Chrome চলছে")
                    .setContentText("ক্লোন Chrome-এর renderer সক্রিয় রাখা হয়েছে")
                    .setSmallIcon(android.R.drawable.sym_def_app_icon)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_MIN);
            startForeground(BlackBoxCore.getHostPkg().hashCode(), builder.build());
            BlackBoxCore.traceInitPublic(BlackBoxCore.getContext(),
                    "v1.0.92 foreground promoted for " + serviceName);
        } catch (Throwable t) {
            // ফোরগ্রাউন্ড ব্যর্থ হলেও bind চলবে — শুধু trace করো।
            try {
                BlackBoxCore.traceInitPublic(BlackBoxCore.getContext(),
                        "v1.0.92 foreground promote failed: " + t);
            } catch (Throwable ignored) {
            }
        }
    }

    public static class P0 extends ProxyService {

    }

    public static class P1 extends ProxyService {

    }

    public static class P2 extends ProxyService {

    }

    public static class P3 extends ProxyService {

    }

    public static class P4 extends ProxyService {

    }

    public static class P5 extends ProxyService {

    }

    public static class P6 extends ProxyService {

    }

    public static class P7 extends ProxyService {

    }

    public static class P8 extends ProxyService {

    }

    public static class P9 extends ProxyService {

    }

    public static class P10 extends ProxyService {

    }

    public static class P11 extends ProxyService {

    }

    public static class P12 extends ProxyService {

    }

    public static class P13 extends ProxyService {

    }

    public static class P14 extends ProxyService {

    }

    public static class P15 extends ProxyService {

    }

    public static class P16 extends ProxyService {

    }

    public static class P17 extends ProxyService {

    }

    public static class P18 extends ProxyService {

    }

    public static class P19 extends ProxyService {

    }

    public static class P20 extends ProxyService {

    }

    public static class P21 extends ProxyService {

    }

    public static class P22 extends ProxyService {

    }

    public static class P23 extends ProxyService {

    }

    public static class P24 extends ProxyService {

    }

    public static class P25 extends ProxyService {

    }

    public static class P26 extends ProxyService {

    }

    public static class P27 extends ProxyService {

    }

    public static class P28 extends ProxyService {

    }

    public static class P29 extends ProxyService {

    }

    public static class P30 extends ProxyService {

    }

    public static class P31 extends ProxyService {

    }

    public static class P32 extends ProxyService {

    }

    public static class P33 extends ProxyService {

    }

    public static class P34 extends ProxyService {

    }

    public static class P35 extends ProxyService {

    }

    public static class P36 extends ProxyService {

    }

    public static class P37 extends ProxyService {

    }

    public static class P38 extends ProxyService {

    }

    public static class P39 extends ProxyService {

    }

    public static class P40 extends ProxyService {

    }

    public static class P41 extends ProxyService {

    }

    public static class P42 extends ProxyService {

    }

    public static class P43 extends ProxyService {

    }

    public static class P44 extends ProxyService {

    }

    public static class P45 extends ProxyService {

    }

    public static class P46 extends ProxyService {

    }

    public static class P47 extends ProxyService {

    }

    public static class P48 extends ProxyService {

    }

    public static class P49 extends ProxyService {

    }
}
