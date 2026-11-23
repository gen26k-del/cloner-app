package top.niunaijun.blackbox.proxy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.RemoteException;

import java.util.HashMap;
import java.util.Map;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.system.am.BActivityManagerService;
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
        intent.setExtrasClassLoader(context.getClassLoader());
        ProxyBroadcastRecord record = ProxyBroadcastRecord.create(intent);
        PendingResult pendingResult = goAsync();
        if (record.mIntent == null) {
            // v1.0.151 FIX: stub extra ছাড়া broadcast = আসল সিস্টেম
            // (বা অন্য অ্যাপ) থেকে আসা raw broadcast, যা
            // BroadcastManager-এর বসানো filter ধরে এখানে এসেছে। আগে
            // এটি নিঃশব্দে ফেলে দেওয়া হতো — ফলে guest-দের manifest
            // receiver সিস্টেম broadcast কখনোই পেত না। এখন সব বিদ্যমান
            // user space-এ ভার্চুয়াল dispatch-এ পাঠানো হয় (শুধু
            // চলমান প্রসেসে ডেলিভারি — scheduleBroadcastReceiver-এর
            // নিয়মই)। server process-এর বাইরে হলে পুরনো আচরণই থাক
            // (স্থানীয় singleton সেখানে অব্যবহৃত/ফাঁকা)।
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
            try {
                BActivityManagerService.get().scheduleBroadcastReceiverAllUsers(
                        intent, new PendingResultData(pendingResult));
            } catch (Throwable e) {
                pendingResult.finish();
            }
            return;
        }
        try {
            BlackBoxCore.getBActivityManager().scheduleBroadcastReceiver(record.mIntent, new PendingResultData(pendingResult), record.mUserId);
        } catch (RemoteException e) {
            pendingResult.finish();
        }
    }
}
