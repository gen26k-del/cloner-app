package top.niunaijun.blackbox.core.system.am;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.os.Build;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.system.pm.BPackage;
import top.niunaijun.blackbox.core.system.pm.BPackageManagerService;
import top.niunaijun.blackbox.core.system.pm.BPackageSettings;
import top.niunaijun.blackbox.core.system.pm.PackageMonitor;
import top.niunaijun.blackbox.entity.am.PendingResultData;
import top.niunaijun.blackbox.proxy.ProxyBroadcastReceiver;
import top.niunaijun.blackbox.utils.Slog;


public class BroadcastManager implements PackageMonitor {
    public static final String TAG = "BroadcastManager";

    public static final int TIMEOUT = 9000;

    private static BroadcastManager sBroadcastManager;

    private final BActivityManagerService mAms;
    private final BPackageManagerService mPms;
    private final Map<String, List<BroadcastReceiver>> mReceivers = new HashMap<>();
    private final Map<String, PendingResultData> mReceiversData = new HashMap<>();
    // v1.0.157: কোন প্যাকেজগুলোর receiver ইতিমধ্যে register হয়েছে —
    // mReceivers লকের ভেতরেই রাখা হয়। deferred startup sweep আর
    // onPackageInstalled একই প্যাকেজ দুইবার register করা ঠেকায়।
    private final Set<String> mRegisteredPackages = new HashSet<>();

    // v1.0.166: broadcast timeout আর :black-এর MAIN LOOPER-এ চলে না।
    // আগে timeout ছিল main-looper Handler-এ — main thread আটকে গেলে
    // timeout-টাই চলতে পারত না, ফলে PendingResult কখনো finish হতো না
    // আর সিস্টেম broadcast ANR দিত। .165-এর AnrExit রেকর্ডে ৫টা ANR-ই
    // SCREEN_ON/OFF broadcast (ProxyBroadcastReceiver) — ঠিক এই গর্ত।
    // DualSpaceLivre (GitHub-এর যাচাইকৃত ফোর্ক) একই সমাধান ব্যবহার
    // করে: dedicated ScheduledExecutorService + প্রতি token-এ একটা
    // ScheduledFuture, TIMEOUT-এ সবসময় finish()। এখানে হুবহু সেই
    // পদ্ধতি — শুধু thread-টা daemon + নামকরা, যাতে :black বন্ধের
    // পথে আটকে না থাকে।
    private final Map<String, ScheduledFuture<?>> mReceiverTimeouts = new HashMap<>();
    private final ScheduledExecutorService mTimeoutExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "BcastTimeout");
                t.setDaemon(true);
                return t;
            });

    public static BroadcastManager startSystem(BActivityManagerService ams, BPackageManagerService pms) {
        if (sBroadcastManager == null) {
            synchronized (BroadcastManager.class) {
                if (sBroadcastManager == null) {
                    sBroadcastManager = new BroadcastManager(ams, pms);
                }
            }
        }
        return sBroadcastManager;
    }

    public BroadcastManager(BActivityManagerService ams, BPackageManagerService pms) {
        mAms = ams;
        mPms = pms;
    }

    public void startup() {
        mPms.addPackageMonitor(this);
        // v1.0.157: receiver registration আর startup-এর critical path-এ
        // নেই। আগে এই loop-টা :black-এর main thread-এ synchronous
        // চলত (SystemCallProvider.onCreate → BlackBoxSystem.startup →
        // BAMS.systemReady → এখানে) — প্রতিটি guest receiver-এর
        // প্রতিটি filter-এর জন্য একটা করে registerReceiver binder
        // call আসল AMS-এ। v1.0.156-এর রিপোর্টে :black main thread
        // ঠিক এই stack-এ 6,011ms আটকে ছিল, আর SCREEN_ON/OFF
        // broadcast-wake-এ :black INIT_FAILURE-এ মরছিল (09:48:54 /
        // 10:04:05, 2026-11-25): process init-এর window-তে startup
        // শেষই হতো না। এখন loop-টা background thread-এ চলে —
        // startup() সাথে সাথে ফেরত আসে, registration একটু পরেই
        // সম্পূর্ণ হয় (শেষে BcastReg ট্রেস)। আচরণ একই: সব receiver
        // শেষ পর্যন্ত register হয়; onPackageInstalled পথও একই
        // registerPackage-ই ব্যবহার করে (নিচের duplicate guard
        // দুই পথের race ঠেকায়)।
        Thread registerThread = new Thread(() -> {
            long startMs = System.currentTimeMillis();
            int registered = 0;
            // v1.0.162: BlackStage — BcastReg thread কোন ধাপে আছে
            // (PmReady-র অপেক্ষা vs আসল registration loop) তা আলাদা
            // লাইনে থাকে; wake-মৃত্যুর সঙ্গে মিলিয়ে পড়তে হবে।
            try {
                BlackBoxCore.traceInitPublic(BlackBoxCore.getContext(),
                        "BlackStage: BcastReg begin (awaiting PmReady)");
            } catch (Throwable ignored) { }
            try {
                // v1.0.161: the PM package scan is itself deferred
                // (PmReady thread) — wait for it before taking the
                // snapshot, or this sweep would see an empty package
                // list and register no guest receivers at all.
                boolean pmReady = mPms.awaitReady(20000);
                try {
                    BlackBoxCore.traceInitPublic(BlackBoxCore.getContext(),
                            "BlackStage: BcastReg pmReady=" + pmReady
                                    + " waited=" + (System.currentTimeMillis() - startMs) + "ms");
                } catch (Throwable ignored) { }
                final List<BPackageSettings> bPackageSettings = mPms.getBPackageSettings();
                for (BPackageSettings bPackageSetting : bPackageSettings) {
                    try {
                        BPackage bPackage = bPackageSetting.pkg;
                        if (bPackage == null) {
                            continue;
                        }
                        // snapshot নেওয়ার পর package uninstall হয়ে
                        // গেলে তার receiver আর register কোরো না।
                        if (mPms.getBPackageSetting(bPackage.packageName) == null) {
                            continue;
                        }
                        registerPackage(bPackage);
                        registered++;
                    } catch (Throwable e) {
                        // একটা package-এর ব্যর্থতা বাকিগুলোকে থামাবে না
                        // (আগের synchronous পথে একটা throw পুরো
                        // BlackBoxSystem.startup-ই ভেঙে দিত)।
                        Slog.e(TAG, "Deferred registerPackage failed", e);
                    }
                }
            } catch (Throwable e) {
                Slog.e(TAG, "Deferred receiver registration failed", e);
            }
            try {
                BlackBoxCore.traceInitPublic(BlackBoxCore.getContext(),
                        "BcastReg: deferred done packages=" + registered
                                + " elapsed=" + (System.currentTimeMillis() - startMs) + "ms");
            } catch (Throwable ignored) { }
        }, "BcastReg");
        registerThread.setDaemon(true);
        registerThread.start();
    }

    private void registerPackage(BPackage bPackage) {
        synchronized (mReceivers) {
            // v1.0.157: duplicate guard — deferred startup sweep আর
            // onPackageInstalled একই package দুইবার register করবে না
            // (দুইবার হলে প্রতিটি broadcast guest-এ দুইবার যেত)।
            if (!mRegisteredPackages.add(bPackage.packageName)) {
                return;
            }
            Slog.d(TAG, "register: " + bPackage.packageName + ", size: " + bPackage.receivers.size());
            for (BPackage.Activity receiver : bPackage.receivers) {
                List<BPackage.ActivityIntentInfo> intents = receiver.intents;
                for (BPackage.ActivityIntentInfo intent : intents) {
                    ProxyBroadcastReceiver proxyBroadcastReceiver = new ProxyBroadcastReceiver();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        BlackBoxCore.getContext().registerReceiver(proxyBroadcastReceiver, intent.intentFilter, Context.RECEIVER_EXPORTED);
                    }else{
                        BlackBoxCore.getContext().registerReceiver(proxyBroadcastReceiver, intent.intentFilter);
                    }
                    addReceiver(bPackage.packageName, proxyBroadcastReceiver);
                }
            }
        }
    }

    private void addReceiver(String packageName, BroadcastReceiver receiver) {
        List<BroadcastReceiver> broadcastReceivers = mReceivers.get(packageName);
        if (broadcastReceivers == null) {
            broadcastReceivers = new ArrayList<>();
            mReceivers.put(packageName, broadcastReceivers);
        }
        broadcastReceivers.add(receiver);
    }

    public void sendBroadcast(PendingResultData pendingResultData) {
        synchronized (mReceiversData) {
            mReceiversData.put(pendingResultData.mBToken, pendingResultData);
            // v1.0.166: একই token-এ পুরনো timeout থাকলে বাতিল করো
            // (DualSpaceLivre পদ্ধতি)।
            ScheduledFuture<?> oldTimeout = mReceiverTimeouts.remove(pendingResultData.mBToken);
            if (oldTimeout != null) {
                oldTimeout.cancel(false);
            }
            ScheduledFuture<?> timeout = mTimeoutExecutor.schedule(() -> {
                try {
                    synchronized (mReceiversData) {
                        mReceiversData.remove(pendingResultData.mBToken);
                        mReceiverTimeouts.remove(pendingResultData.mBToken);
                    }
                    pendingResultData.build().finish();
                    Slog.d(TAG, "Timeout Receiver: " + pendingResultData);
                    try {
                        BlackBoxCore.traceInitPublic(BlackBoxCore.getContext(),
                                "BcastTimeout: forced finish after " + TIMEOUT + "ms");
                    } catch (Throwable ignored) { }
                } catch (Throwable ignored) {
                }
            }, TIMEOUT, TimeUnit.MILLISECONDS);
            mReceiverTimeouts.put(pendingResultData.mBToken, timeout);
        }
    }

    public void finishBroadcast(PendingResultData data) {
        synchronized (mReceiversData) {
            // v1.0.166: record সরাও + timeout বাতিল করো (আগে শুধু
            // main-looper message সরানো হতো, record থেকে যেত)।
            mReceiversData.remove(data.mBToken);
            ScheduledFuture<?> timeout = mReceiverTimeouts.remove(data.mBToken);
            if (timeout != null) {
                timeout.cancel(false);
            }
        }
    }

    @Override
    public void onPackageUninstalled(String packageName, boolean removeApp, int userId) {
        if (removeApp) {
            synchronized (mReceivers) {
                List<BroadcastReceiver> broadcastReceivers = mReceivers.get(packageName);
                if (broadcastReceivers != null) {
                    Slog.d(TAG, "unregisterReceiver Package: " + packageName + ", size: " + broadcastReceivers.size());
                    for (BroadcastReceiver broadcastReceiver : broadcastReceivers) {
                        try {
                            BlackBoxCore.getContext().unregisterReceiver(broadcastReceiver);
                        } catch (Throwable ignored) {
                        }
                    }
                }
                mReceivers.remove(packageName);
                // v1.0.157: guard set থেকেও সরাও — নইলে reinstall-এর
                // পর registerPackage skip হয়ে যাবে।
                mRegisteredPackages.remove(packageName);
            }
        }
    }

    @Override
    public void onPackageInstalled(String packageName, int userId) {
        synchronized (mReceivers) {
            mReceivers.remove(packageName);
            // v1.0.157: নতুন install-এর receiver নতুন করে register
            // হতে হবে — guard set-এর পুরনো entry সরাও।
            mRegisteredPackages.remove(packageName);
            BPackageSettings bPackageSetting = mPms.getBPackageSetting(packageName);
            if (bPackageSetting != null) {
                registerPackage(bPackageSetting.pkg);
            }
        }
    }
}
