package top.niunaijun.blackbox.core.system.am;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.IEmpty;
import top.niunaijun.blackbox.core.system.BProcessManagerService;
import top.niunaijun.blackbox.core.system.ProcessRecord;
import top.niunaijun.blackbox.core.system.pm.BPackageManagerService;
import top.niunaijun.blackbox.entity.UnbindRecord;
import top.niunaijun.blackbox.entity.am.RunningServiceInfo;
import top.niunaijun.blackbox.proxy.ProxyManifest;
import top.niunaijun.blackbox.proxy.record.ProxyServiceRecord;


@SuppressLint("NewApi")
public class ActiveServices {
    public static final String TAG = "ActiveServices";

    // Isolated-service rebind-এ unique processName দেওয়ার কাউন্টার।
    private static final AtomicInteger sIsolatedBindCounter = new AtomicInteger(0);

    private final Map<Intent.FilterComparison, RunningServiceRecord> mRunningServiceRecords = new HashMap<>();
    private final Map<IBinder, RunningServiceRecord> mRunningTokens = new HashMap<>();
    private final Map<IBinder, ConnectedServiceRecord> mConnectedServices = new HashMap<>();

    public void startService(Intent intent, String resolvedType, boolean requireForeground, int userId) {
        ResolveInfo resolveInfo = resolveService(intent, resolvedType, userId);
        if (resolveInfo == null)
            return;

        ServiceInfo serviceInfo = resolveInfo.serviceInfo;
        ProcessRecord processRecord = BProcessManagerService.get().startProcessLocked(serviceInfo.packageName, serviceInfo.processName, userId, -1, Binder.getCallingPid());
        if (processRecord == null) {
            throw new RuntimeException("Unable to create " + serviceInfo.name);
        }
        RunningServiceRecord runningServiceRecord = getOrCreateRunningServiceRecord(intent);
        runningServiceRecord.mServiceInfo = serviceInfo;

        runningServiceRecord.getAndIncrementStartId();
        final Intent stubServiceIntent = createStubServiceIntent(intent, serviceInfo, processRecord, runningServiceRecord);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BlackBoxCore.getContext().startService(stubServiceIntent);
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    public int stopService(Intent intent, String resolvedType, int userId) {

        synchronized (mRunningServiceRecords) {
            RunningServiceRecord runningServiceRecord = findRunningServiceRecord(intent);
            if (runningServiceRecord == null) {
                return 0;
            }
            if (runningServiceRecord.mBindCount.get() > 0) {
                Log.d(TAG, "There are also connections");
                return 0;
            }
            runningServiceRecord.mStartId.set(0);
            ResolveInfo resolveInfo = resolveService(intent, resolvedType, userId);
            if (resolveInfo == null)
                return 0;
            ServiceInfo serviceInfo = resolveInfo.serviceInfo;
            ProcessRecord processRecord = BProcessManagerService.get().startProcessLocked(serviceInfo.packageName, serviceInfo.processName, userId, -1, Binder.getCallingPid());
            if (processRecord == null) {
                return 0;
            }
            try {
                processRecord.bActivityThread.stopService(intent);
            } catch (RemoteException ignored) {
            }
        }
        return 0;
    }

    public Intent bindService(Intent intent, final IBinder binder, String resolvedType, int userId) {
        ResolveInfo resolveInfo = resolveService(intent, resolvedType, userId);
        if (resolveInfo == null)
            return intent;
        ServiceInfo serviceInfo = resolveInfo.serviceInfo;
        // Chromium child service (renderer/GPU/privileged) — shared RELRO বন্ধ:
        // Browser যে random base load address দেয়, renderer stub-এর ওই ঠিকানা
        // আগেই দখল থাকে (CloneMe/BlackBox/ART mapping) বলে libchrome.so-র
        // ২০৬MB RELRO সংরক্ষণ ব্যর্থ হয় ("reserved address space 5238784
        // smaller than 206565376 bytes needed") আর renderer মরে "Aw, Snap!"
        // আসে (v1.0.72 স্ক্রিনশট 14:32)। Shared RELRO-র তথ্য
        // (ChromiumLinkerParams + EXTRA_LINKER_SHARED_RELROS Bundle) intent
        // থেকে সরিয়ে দিলে child disableSharedRelros() করে libchrome.so
        // সাধারণভাবে (random address-এ) লোড করে — fixed address সংরক্ষণ
        // লাগে না। শুধু RAM বাঁচানোর optimization হারায়, কাজের কিছু না।
        if (serviceInfo.name != null && serviceInfo.name.contains("ProcessService")
                && serviceInfo.packageName != null
                && (serviceInfo.packageName.contains("chrome") || serviceInfo.packageName.contains("brave"))) {
            intent.removeExtra("org.chromium.base.android.linker.shared_relros");
            Bundle extras = intent.getExtras();
            if (extras != null) {
                java.util.ArrayList<String> linkerKeys = new java.util.ArrayList<>();
                for (String key : extras.keySet()) {
                    if (key != null && key.toLowerCase(java.util.Locale.ROOT).contains("linker")) {
                        linkerKeys.add(key);
                    }
                }
                for (String key : linkerKeys) {
                    intent.removeExtra(key);
                }
                if (!linkerKeys.isEmpty()) {
                    Log.d(TAG, "bindService chromium child: disabled shared RELRO for " + serviceInfo.name);
                }
            }
        }
        // Isolated service-এর প্রতিটি instance (Chromium renderer/GPU) আলাদা
        // প্রসেস চায়। instanceName null করায় (v1.0.49) সব instance একই
        // processName-এ মিশে যেত আর startProcessLocked আগের renderer-ই ফেরত
        // দিত — নেভিগেশনের নতুন renderer কখনো শুরু হয় না, পেজ ফ্রিজ থাকে।
        // instanceName '1','2',... থাকলে processName-এ suffix যোগ করে আলাদা
        // প্রসেস বানাও। '0' (প্রথম renderer) আগের নামেই থাকে, যাতে নতুন ট্যাব
        // রেন্ডারের প্রমাণিত আচরণ না বদলায়।
        String processName = serviceInfo.processName;
        String instanceName = intent.getStringExtra("_B_instanceName");
        if (instanceName != null && !instanceName.isEmpty() && !"0".equals(instanceName)
                && processName != null) {
            processName = processName + "_i" + instanceName;
            Log.d(TAG, "bindService isolated instance=" + instanceName + " process=" + processName);
        }
        // Isolated service bind (Chromium child process) — প্রতিটি bind-এ
        // নতুন প্রসেস চাই। startProcessLocked আগের ProcessRecord reuse
        // করলে একই OS প্রসেসে SandboxedProcessService আবার বানাতে গিয়ে
        // Chromium "Illegal child process reuse" বলে অস্বীকার করে — renderer
        // মরে, Chrome নেটিভ ক্র্যাশ করে / স্লো হয়। কিন্তু পুরনো রেকর্ড
        // মেরে ফেলা যাবে না (v1.0.62-র ভুল): Chrome চালু হওয়ার সময়ই একই
        // renderer দুবার bind করে, আর দ্বিতীয় bind-এ প্রথম renderer-টা
        // তখনো সক্রিয় থাকে — সেটা মারলে Chrome-ই মরে (10:35: দুবার bind,
        // ১৪ সেকেন্ড পর Chrome বন্ধ)। আবার কিছু না মেরে শুধু suffix দিলেও
        // (v1.0.63) ব্যবহার-না-হওয়া renderer stub জমতে থাকে (10:45-এ ৩৪+
        // stub চালু, :black ভারী হয়ে :p4-র init ১৮ সেকেন্ড আটকে যায়,
        // YouTube-এ "Aw, Snap!")। তাই: পুরনো stub মরা হলে, বা কেউ আর bind
        // করে না থাকলে (bindCount 0), সেটা মেরে নামটা ফেরত নাও; সক্রিয়
        // থাকলে unique suffix দিয়ে নতুন প্রসেস বানাও।
        if (instanceName != null && !instanceName.isEmpty() && processName != null) {
            ProcessRecord existing = BProcessManagerService.get().findProcessRecord(
                    serviceInfo.packageName, processName, userId);
            if (existing != null) {
                boolean reclaim = false;
                if (existing.bActivityThread != null
                        && !existing.bActivityThread.asBinder().isBinderAlive()) {
                    reclaim = true;
                } else if (existing.bActivityThread != null) {
                    RunningServiceRecord rsr = findRunningServiceRecord(intent);
                    if (rsr == null || rsr.mBindCount.get() == 0) {
                        reclaim = true;
                    }
                }
                if (reclaim) {
                    Log.d(TAG, "bindService isolated: reclaiming unused " + processName);
                    BProcessManagerService.get().onProcessDie(existing);
                } else {
                    processName = processName + "_r" + sIsolatedBindCounter.incrementAndGet();
                    Log.d(TAG, "bindService isolated rebind, fresh process=" + processName);
                }
            }
        }
        // v1.0.67-র non-isolated child reclaim সরানো হয়েছে (v1.0.71):
        // idle stub (mBindCount 0) মারলে সব সাইটে "Can't open this page"
        // আসে — mBindCount 0 মানেই stub অলস নয়, GPU/privileged প্রসেস
        // bind ছাড়াও ব্রাউজারের কাজ (compositing / নেটওয়ার্ক) করে; সেটা
        // মারলে নেভিগেশনই ব্যর্থ হয়। "Can't open this page" v1.0.67
        // (reclaim যোগের) পর থেকেই শুরু, তার আগে "Aw, Snap!" ছিল।
        // মরা binder-এর reclaim v1.0.57-র isBinderAlive চেকেই হয়।
        ProcessRecord processRecord = BProcessManagerService.get().startProcessLocked(
                serviceInfo.packageName,
                processName,
                userId,
                -1, Binder.getCallingPid());

        if (processRecord == null) {
            // stub প্রসেস চালু হয়নি (যেমন cold start-এ provider call দেরি)।
            // RuntimeException ছুড়লে BindServiceCommon আসল intent-এ ফলব্যাক
            // করে আর আসল AM SecurityException দেয় — Chrome FATAL ক্র্যাশ করে।
            // null ফেরত দাও, যাতে BindServiceCommon bind ব্যর্থ (0) দেয় আর
            // Chrome retry করতে পারে।
            Log.w(TAG, "bindService: unable to create " + serviceInfo.name);
            return null;
        }

        RunningServiceRecord runningServiceRecord;
        synchronized (mRunningServiceRecords) {
            runningServiceRecord = getOrCreateRunningServiceRecord(intent);
            runningServiceRecord.mServiceInfo = serviceInfo;

            if (binder != null) {
                ConnectedServiceRecord connectedService = mConnectedServices.get(binder);
                boolean isBound = false;
                if (connectedService != null) {
                    isBound = true;
                } else {
                    connectedService = new ConnectedServiceRecord();
                    try {
                        binder.linkToDeath(new IBinder.DeathRecipient() {
                            @Override
                            public void binderDied() {
                                binder.unlinkToDeath(this, 0);
                                mConnectedServices.remove(binder);
                            }
                        }, 0);
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                    connectedService.mIBinder = binder;
                    connectedService.mIntent = intent;
                    mConnectedServices.put(binder, connectedService);
                }

                if (!isBound) {
                    runningServiceRecord.incrementBindCountAndGet();
                }
                runningServiceRecord.mConnectedServiceRecord = connectedService;
            }
        }
        return createStubServiceIntent(intent, serviceInfo, processRecord, runningServiceRecord);
    }

    public void unbindService(IBinder binder, int userId) {
        ConnectedServiceRecord connectedService = mConnectedServices.get(binder);
        if (connectedService == null) {
            return;
        }
        RunningServiceRecord runningServiceRecord = getOrCreateRunningServiceRecord(connectedService.mIntent);
        runningServiceRecord.mConnectedServiceRecord = null;
        runningServiceRecord.mBindCount.decrementAndGet();
        mConnectedServices.remove(binder);
    }

    public void stopServiceToken(ComponentName className, IBinder token, int userId) {
        RunningServiceRecord runningServiceByToken = findRunningServiceByToken(token);
        if (runningServiceByToken != null) {
            stopService(runningServiceByToken.mIntent, null, userId);
        }
    }

    public void onStartCommand(Intent proxyIntent, int userId) {
    }

    public void onServiceDestroy(Intent proxyIntent, int userId) {
        if (proxyIntent == null)
            return;
        ProxyServiceRecord proxyServiceRecord = ProxyServiceRecord.create(proxyIntent);
        if (proxyServiceRecord.mServiceIntent != null) {
            proxyIntent = proxyServiceRecord.mServiceIntent;
        }
        RunningServiceRecord remove = mRunningServiceRecords.remove(new Intent.FilterComparison(proxyIntent));
        if (remove != null) {
            mRunningTokens.remove(remove);
        }
    }

    public UnbindRecord onServiceUnbind(Intent proxyIntent, int userId) throws RemoteException {
        if (proxyIntent == null)
            return null;
        ProxyServiceRecord proxyServiceRecord = ProxyServiceRecord.create(proxyIntent);
        ComponentName component = proxyServiceRecord.mServiceIntent.getComponent();

        RunningServiceRecord runningServiceRecord = findRunningServiceRecord(proxyServiceRecord.mServiceIntent);
        if (runningServiceRecord == null)
            return null;
        UnbindRecord record = new UnbindRecord();
        record.setComponentName(component);
        record.setBindCount(runningServiceRecord.mBindCount.get());
        record.setStartId(runningServiceRecord.mStartId.get());
        return record;
    }

    private Intent createStubServiceIntent(Intent targetIntent, ServiceInfo serviceInfo, ProcessRecord processRecord, RunningServiceRecord runningServiceRecord) {
        Intent stub = new Intent();
        ComponentName stubComp = new ComponentName(BlackBoxCore.getHostPkg(), ProxyManifest.getProxyService(processRecord.bpid));
        stub.setComponent(stubComp);
        stub.setAction(UUID.randomUUID().toString());
        ProxyServiceRecord.saveStub(stub, targetIntent, serviceInfo, runningServiceRecord, processRecord.userId, runningServiceRecord.mStartId.get());
        return stub;
    }

    private RunningServiceRecord getOrCreateRunningServiceRecord(Intent intent) {
        RunningServiceRecord runningServiceRecord = findRunningServiceRecord(intent);
        if (runningServiceRecord == null) {
            runningServiceRecord = new RunningServiceRecord();
            runningServiceRecord.mIntent = intent;
            mRunningServiceRecords.put(new Intent.FilterComparison(intent), runningServiceRecord);
            mRunningTokens.put(runningServiceRecord, runningServiceRecord);
        }
        return runningServiceRecord;
    }

    private RunningServiceRecord findRunningServiceRecord(Intent intent) {
        return mRunningServiceRecords.get(new Intent.FilterComparison(intent));
    }

    private RunningServiceRecord findRunningServiceByToken(IBinder token) {
        return mRunningTokens.get(token);
    }

    public RunningServiceInfo getRunningServiceInfo(String callerPackage, int userId) {
        ActivityManager manager = (ActivityManager)
                BlackBoxCore.getContext().getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningServiceInfo> runningServices = manager.getRunningServices(Integer.MAX_VALUE);
        Map<Integer, ActivityManager.RunningServiceInfo> serviceInfoMap = new HashMap<>();
        for (ActivityManager.RunningServiceInfo runningService : runningServices) {
            serviceInfoMap.put(runningService.pid, runningService);
        }

        RunningServiceInfo info = new RunningServiceInfo();
        for (RunningServiceRecord value : mRunningServiceRecords.values()) {
            ServiceInfo serviceInfo = value.mServiceInfo;
            ProcessRecord processRecord = BProcessManagerService.get().findProcessRecord(callerPackage, serviceInfo.processName, userId);
            if (processRecord == null)
                continue;
            ActivityManager.RunningServiceInfo runningServiceInfo = serviceInfoMap.get(processRecord.pid);
            if (runningServiceInfo != null) {
                runningServiceInfo.process = processRecord.processName;
                runningServiceInfo.service = new ComponentName(serviceInfo.packageName, serviceInfo.name);
                info.mRunningServiceInfoList.add(runningServiceInfo);
            }
        }
        return info;
    }

    public IBinder peekService(Intent intent, String resolvedType, int userId) {
        ResolveInfo resolveInfo = resolveService(intent, resolvedType, userId);
        if (resolveInfo == null)
            return null;
        ProcessRecord processRecord =
                BProcessManagerService.get().findProcessRecord(resolveInfo.serviceInfo.packageName, resolveInfo.serviceInfo.processName, userId);
        if (processRecord == null)
            return null;
        try {
            return processRecord.bActivityThread.peekService(intent);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return null;
    }

    private ResolveInfo resolveService(Intent intent, String resolvedType, int userId) {
        return BPackageManagerService.get().resolveService(intent, 0, resolvedType, userId);
    }

    public static class RunningServiceRecord extends IEmpty.Stub {
        
        private final AtomicInteger mStartId = new AtomicInteger(1);
        private final AtomicInteger mBindCount = new AtomicInteger(0);
        
        private ConnectedServiceRecord mConnectedServiceRecord;

        private ServiceInfo mServiceInfo;
        private Intent mIntent;

        public int getAndIncrementStartId() {
            return mStartId.getAndIncrement();
        }

        public int decrementBindCountAndGet() {
            return mBindCount.decrementAndGet();
        }

        public int incrementBindCountAndGet() {
            return mBindCount.incrementAndGet();
        }
    }

    public static class ConnectedServiceRecord {
        private IBinder mIBinder;
        private Intent mIntent;
    }
}
