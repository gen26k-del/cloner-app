package top.niunaijun.blackbox.core.system.am;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
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
        // মরে, Chrome নেটিভ ক্র্যাশ করে / স্লো হয় (relaunch-এই হয়, কারণ
        // আগের সেশনের renderer stub তখনো বেঁচে থাকে)। তাই পুরনো রেকর্ড
        // থাকলে সেটা মেরে সরিয়ে দাও, যাতে নতুন প্রসেস বানায়।
        if (instanceName != null && !instanceName.isEmpty() && processName != null) {
            ProcessRecord existing = BProcessManagerService.get().findProcessRecord(
                    serviceInfo.packageName, processName, userId);
            if (existing != null) {
                Log.d(TAG, "bindService isolated: dropping stale " + processName);
                BProcessManagerService.get().onProcessDie(existing);
            }
        }
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
