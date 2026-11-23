package top.niunaijun.blackbox.proxy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.RemoteException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.system.user.BUserInfo;
import top.niunaijun.blackbox.entity.am.PendingResultData;
import top.niunaijun.blackbox.proxy.record.ProxyBroadcastRecord;


public class ProxyBroadcastReceiver extends BroadcastReceiver {
    public static final String TAG = "ProxyBroadcastReceiver";

    // v1.0.151: একই raw broadcast-এর ডুপ্লিকেট ঠেকানো। BroadcastManager
    // প্রতিটি guest receiver-এর প্রতিটি intent-filter-এর জন্য আলাদা
    // ProxyBroadcastReceiver বসায় (:black-এ), তাই একই action-এর
    // broadcast একাধিক instance-এ একসাথে আসতে পারে — fan-out একবারই
    // হওয়া উচিত। key-তে google.message_id থাকলে আলাদা FCM মেসেজ
    // আলাদাই থাকে।
    private static final long RAW_DEDUPE_WINDOW_MS = 2000;
    private static final Map<String, Long> sRecentRawBroadcasts = new HashMap<>();

    private static boolean isPushAction(String action) {
        return "com.google.android.c2dm.intent.RECEIVE".equals(action)
                || "com.google.android.c2dm.intent.REGISTRATION".equals(action)
                || "com.google.firebase.INSTANCE_ID_EVENT".equals(action);
    }

    private static boolean isDuplicateRaw(Intent intent) {
        String key = intent.getAction() + "|" + intent.getPackage() + "|"
                + intent.getComponent() + "|" + intent.getDataString() + "|"
                + intent.getStringExtra("google.message_id");
        long now = System.currentTimeMillis();
        synchronized (sRecentRawBroadcasts) {
            Long last = sRecentRawBroadcasts.get(key);
            if (last != null && now - last < RAW_DEDUPE_WINDOW_MS) {
                return true;
            }
            sRecentRawBroadcasts.put(key, now);
            if (sRecentRawBroadcasts.size() > 64) {
                sRecentRawBroadcasts.clear();
                sRecentRawBroadcasts.put(key, now);
            }
            return false;
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // v1.0.154: পুরো body একটা Throwable guard-এর ভেতরে (শেষের
        // catch দেখো) — wake broadcast-এ কোনো Error/Exception ছিটকে
        // গেলে আগে প্রসেসটাই মরে যেত (INIT_FAILURE; 2026-11-23
        // 20:25-এর SCREEN_OFF মৃত্যুগুলো এই পরিবারের)। এখন
        // BlackInitFail ট্রেস রেখে swallow হয় — একটা broadcast
        // হারানো recoverable, প্রসেস বেঁচে থাকে।
        ProxyBroadcastRecord record = null;
        PendingResult pendingResult = null;
        try {
        intent.setExtrasClassLoader(context.getClassLoader());
        // v1.0.152: :black প্রসেস মৃত থাকলে SCREEN_ON/OFF broadcast-ই
        // তাকে জাগায় — আর 2026-11-23-র রিপোর্টে ঠিক এই দুই wake-এই
        // :black INIT_FAILURE-এ মরেছে (18:38 SCREEN_OFF, 18:58
        // SCREEN_ON)। wake-এর মুহূর্তটা ট্রেসে রাখো, যাতে পরের
        // রিপোর্টে wake → init marker (doCreate start/completed,
        // BlackInit) → মৃত্যুর রেকর্ড পাশাপাশি মেলানো যায়। শুধু এই
        // দুই action-এই লেখা হয় — অন্য broadcast-এ কোনো খরচ নেই।
        try {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(action) || Intent.ACTION_SCREEN_OFF.equals(action)) {
                BlackBoxCore.traceInitPublic(context, "BlackWake: action=" + action);
            }
        } catch (Throwable ignored) { }
        record = ProxyBroadcastRecord.create(intent);
        pendingResult = goAsync();
        if (record.mIntent == null) {
            // v1.0.151 FIX: stub extra ছাড়া broadcast = আসল সিস্টেম
            // (বা অন্য অ্যাপ) থেকে আসা raw broadcast, যা
            // BroadcastManager-এর বসানো filter ধরে এখানে এসেছে। আগে
            // এটি নিঃশব্দে ফেলে দেওয়া হতো — ফলে guest-দের manifest
            // receiver সিস্টেম broadcast কখনোই পেত না (যেমন GMS-এর
            // C2DM RECEIVE, যা FCM ডেলিভারির প্রথম ধাপ)। এখন প্রতিটি
            // বিদ্যমান user-এর জন্য ভার্চুয়াল PM-এ matching manifest
            // receiver থাকলে পুরনো scheduleBroadcastReceiver পথেই
            // dispatch হয় (শুধু চলমান প্রসেসে ডেলিভারি — ওই পথের
            // নিয়মই)। server process-এর বাইরে হলে পুরনো আচরণই থাক
            // (fan-out শুধু :black থেকেই নিরাপদ)।
            boolean push = isPushAction(intent.getAction());
            boolean serverProcess;
            try {
                serverProcess = BlackBoxCore.get().isServerProcess();
            } catch (Throwable e) {
                serverProcess = false;
            }
            if (!serverProcess) {
                if (push) {
                    BlackBoxCore.traceInitPublic(context,
                            "PushRx: dropped (not server process) action=" + intent.getAction());
                }
                pendingResult.finish();
                return;
            }
            boolean duplicate = isDuplicateRaw(intent);
            if (push) {
                BlackBoxCore.traceInitPublic(context,
                        "PushRx: raw action=" + intent.getAction()
                                + " pkg=" + intent.getPackage()
                                + (duplicate ? " duplicate-skipped" : " forwarding"));
            }
            if (duplicate) {
                pendingResult.finish();
                return;
            }
            boolean handedOff = false;
            try {
                List<BUserInfo> users = BlackBoxCore.get().getUsers();
                if (users != null) {
                    for (BUserInfo user : users) {
                        if (user == null) {
                            continue;
                        }
                        List<ResolveInfo> resolves = BlackBoxCore.getBPackageManager()
                                .queryBroadcastReceivers(intent, PackageManager.GET_META_DATA, null, user.id);
                        if (resolves == null || resolves.isEmpty()) {
                            continue;
                        }
                        BlackBoxCore.getBActivityManager().scheduleBroadcastReceiver(
                                intent, new PendingResultData(pendingResult), user.id);
                        handedOff = true;
                    }
                }
            } catch (Throwable e) {
                // fan-out ব্যর্থ হলে নিচে finish — broadcast হারালেও
                // :black প্রসেস যেন আটকে না থাকে।
            }
            if (!handedOff) {
                pendingResult.finish();
            }
            return;
        }
        try {
            BlackBoxCore.getBActivityManager().scheduleBroadcastReceiver(record.mIntent, new PendingResultData(pendingResult), record.mUserId);
        } catch (RemoteException e) {
            pendingResult.finish();
        }
        } catch (Throwable e) {
            // v1.0.154: BlackInitFail — ঠিক কোন throwable, কোন
            // action-এ, তা ট্রেসে থাকল; প্রসেস মরল না। goAsync নেওয়া
            // হয়ে থাকলে pendingResult শেষ করে দাও, নইলে broadcast
            // timeout হবে।
            try {
                BlackBoxCore.traceInitPublic(context,
                        "BlackInitFail: receiver wake action=" + intent.getAction()
                                + " -> " + e.getClass().getName() + ": " + e.getMessage());
            } catch (Throwable ignored) { }
            if (pendingResult != null) {
                try { pendingResult.finish(); } catch (Throwable ignored) { }
            }
        }
    }
}
