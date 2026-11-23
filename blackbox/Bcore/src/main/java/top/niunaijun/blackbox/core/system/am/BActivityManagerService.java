package top.niunaijun.blackbox.core.system.am;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.system.BProcessManagerService;
import top.niunaijun.blackbox.core.system.ISystemService;
import top.niunaijun.blackbox.core.system.ProcessRecord;
import top.niunaijun.blackbox.core.system.pm.BPackageManagerService;
import top.niunaijun.blackbox.entity.AppConfig;
import top.niunaijun.blackbox.entity.UnbindRecord;
import top.niunaijun.blackbox.entity.am.PendingResultData;
import top.niunaijun.blackbox.entity.am.ReceiverData;
import top.niunaijun.blackbox.entity.am.RunningAppProcessInfo;
import top.niunaijun.blackbox.entity.am.RunningServiceInfo;
import top.niunaijun.blackbox.utils.Slog;

import static android.content.pm.PackageManager.GET_META_DATA;


public class BActivityManagerService extends IBActivityManagerService.Stub implements ISystemService {
    public static final String TAG = "BActivityManagerService";
    private static final BActivityManagerService sService = new BActivityManagerService();
    private final Map<Integer, UserSpace> mUserSpace = new HashMap<>();
    private final BPackageManagerService mPms = BPackageManagerService.get();
    private final BroadcastManager mBroadcastManager;

    public static BActivityManagerService get() {
        return sService;
    }

    public BActivityManagerService() {
        mBroadcastManager = BroadcastManager.startSystem(this, mPms);
    }

    @Override
    public ComponentName startService(Intent intent, String resolvedType, boolean requireForeground, int userId) {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            userSpace.mActiveServices.startService(intent, resolvedType, requireForeground, userId);
        }
        return null;
    }

    @Override
    public IBinder acquireContentProviderClient(ProviderInfo providerInfo) throws RemoteException {
        int callingPid = Binder.getCallingPid();
        ProcessRecord processRecord = BProcessManagerService.get().startProcessLocked(providerInfo.packageName,
                providerInfo.processName,
                BProcessManagerService.get().getUserIdByCallingPid(callingPid),
                -1,
                Binder.getCallingPid());
        if (processRecord == null) {
            throw new RuntimeException("Unable to create process " + providerInfo.name);
        }
        try {
            return processRecord.bActivityThread.acquireContentProviderClient(providerInfo);
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        }
    }

    @Override
    public Intent sendBroadcast(Intent intent, String resolvedType, int userId) throws RemoteException {
        List<ResolveInfo> resolves = BPackageManagerService.get().queryBroadcastReceivers(intent, GET_META_DATA, resolvedType, userId);

        for (ResolveInfo resolve : resolves) {
            ProcessRecord processRecord = BProcessManagerService.get().findProcessRecord(resolve.activityInfo.packageName, resolve.activityInfo.processName, userId);
            if (processRecord == null) {
                continue;
            }
            try {
                processRecord.bActivityThread.bindApplication();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        Intent shadow = new Intent();
        shadow.setPackage(BlackBoxCore.getHostPkg());
        shadow.setComponent(null);
        shadow.setAction(intent.getAction());
        return shadow;
    }

    @Override
    public IBinder peekService(Intent intent, String resolvedType, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            return userSpace.mActiveServices.peekService(intent, resolvedType, userId);
        }
    }

    @Override
    public void onActivityCreated(int taskId, IBinder token, IBinder activityRecord) throws RemoteException {
        int callingPid = Binder.getCallingPid();
        ProcessRecord process = BProcessManagerService.get().findProcessByPid(callingPid);
        if (process == null) {
            return;
        }
        ActivityRecord record = (ActivityRecord) activityRecord;
        UserSpace userSpace = getOrCreateSpaceLocked(process.userId);
        synchronized (userSpace.mStack) {
            userSpace.mStack.onActivityCreated(process, taskId, token, record);
        }
    }

    @Override
    public void onActivityResumed(IBinder token) throws RemoteException {
        int callingPid = Binder.getCallingPid();
        ProcessRecord process = BProcessManagerService.get().findProcessByPid(callingPid);
        if (process == null) {
            return;
        }
        UserSpace userSpace = getOrCreateSpaceLocked(process.userId);
        synchronized (userSpace.mStack) {
            userSpace.mStack.onActivityResumed(process.userId, token);
        }
    }

    @Override
    public void onActivityDestroyed(IBinder token) throws RemoteException {
        int callingPid = Binder.getCallingPid();
        ProcessRecord process = BProcessManagerService.get().findProcessByPid(callingPid);
        if (process == null) {
            return;
        }
        UserSpace userSpace = getOrCreateSpaceLocked(process.userId);
        synchronized (userSpace.mStack) {
            userSpace.mStack.onActivityDestroyed(process.userId, token);
        }
    }

    @Override
    public void onFinishActivity(IBinder token) throws RemoteException {
        int callingPid = Binder.getCallingPid();
        ProcessRecord process = BProcessManagerService.get().findProcessByPid(callingPid);
        if (process == null) {
            return;
        }
        UserSpace userSpace = getOrCreateSpaceLocked(process.userId);
        synchronized (userSpace.mStack) {
            userSpace.mStack.onFinishActivity(process.userId, token);
        }
    }

    @Override
    public RunningAppProcessInfo getRunningAppProcesses(String callerPackage, int userId) throws RemoteException {
        ActivityManager manager = (ActivityManager)
                BlackBoxCore.getContext().getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> runningAppProcesses = manager.getRunningAppProcesses();
        Map<Integer, ActivityManager.RunningAppProcessInfo> runningProcessMap = new HashMap<>();
        for (ActivityManager.RunningAppProcessInfo runningProcess : runningAppProcesses) {
            runningProcessMap.put(runningProcess.pid, runningProcess);
        }
        List<ProcessRecord> packageProcessAsUser = BProcessManagerService.get().getPackageProcessAsUser(callerPackage, userId);

        RunningAppProcessInfo appProcessInfo = new RunningAppProcessInfo();
        for (ProcessRecord processRecord : packageProcessAsUser) {
            ActivityManager.RunningAppProcessInfo runningAppProcessInfo = runningProcessMap.get(processRecord.pid);
            if (runningAppProcessInfo != null) {
                runningAppProcessInfo.processName = processRecord.processName;
                appProcessInfo.mAppProcessInfoList.add(runningAppProcessInfo);
            }
        }
        return appProcessInfo;
    }

    @Override
    public RunningServiceInfo getRunningServices(String callerPackage, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        return userSpace.mActiveServices.getRunningServiceInfo(callerPackage, userId);
    }

    @Override
    public void scheduleBroadcastReceiver(Intent intent, PendingResultData pendingResultData, int userId) throws RemoteException {
        List<ResolveInfo> resolves = BPackageManagerService.get().queryBroadcastReceivers(intent, GET_META_DATA, null, userId);

        if (resolves.isEmpty()) {
            pendingResultData.build().finish();
            Slog.d(TAG, "scheduleBroadcastReceiver empty");
            return;
        }
        mBroadcastManager.sendBroadcast(pendingResultData);
        deliverBroadcastToResolves(intent, pendingResultData, resolves, userId);
    }

    private void deliverBroadcastToResolves(Intent intent, PendingResultData pendingResultData, List<ResolveInfo> resolves, int userId) {
        for (ResolveInfo resolve : resolves) {
            ProcessRecord processRecord = BProcessManagerService.get().findProcessRecord(resolve.activityInfo.packageName, resolve.activityInfo.processName, userId);
            // bActivityThread null হতে পারে — প্রসেস চালু হয়েছে কিন্তু
            // ActivityThread binder এখনো বসেনি (যেমন Lite-এ দেখা গেছে:
            // scheduleReceiver on a null object reference)। null হলে
            // broadcast এড়িয়ে যাও, NPE দিয়ে :black প্রসেস নষ্ট করো না।
            if (processRecord != null && processRecord.bActivityThread != null) {
                ReceiverData data = new ReceiverData();
                data.intent = intent;
                data.activityInfo = resolve.activityInfo;
                data.data = pendingResultData;
                processRecord.bActivityThread.scheduleReceiver(data);
            }
        }
    }

    // v1.0.151: আসল সিস্টেম থেকে আসা ব্রডকাস্ট (stub extra ছাড়া) সব
    // বিদ্যমান user space-এর guest manifest receiver-দের কাছে পৌঁছে
    // দাও। ProxyBroadcastReceiver আগে এগুলো নিঃশব্দে ফেলে দিত, তাই
    // BroadcastManager-এর registerPackage দিয়ে বসানো :black
    // receiver-গুলো (প্রতিটি guest-এর manifest filter) কার্যত অকেজো
    // ছিল — guest-দের manifest receiver সিস্টেম ব্রডকাস্ট কখনোই
    // পেত না (runtime receiver-রা RegisterReceiver hook দিয়ে আলাদা
    // পথে পায়)। শুধু server process থেকেই ডাকতে হবে (স্থানীয়
    // singleton)। scheduleBroadcastReceiver-এর মতোই এখানেও শুধু
    // চলমান প্রসেসে ডেলিভারি হয় — ব্রডকাস্টের জন্য নতুন প্রসেস
    // তোলা হয় না।
    public void scheduleBroadcastReceiverAllUsers(Intent intent, PendingResultData pendingResultData) {
        List<Integer> userIds;
        synchronized (mUserSpace) {
            userIds = new ArrayList<>(mUserSpace.keySet());
        }
        boolean anyDelivered = false;
        for (int userId : userIds) {
            List<ResolveInfo> resolves = BPackageManagerService.get().queryBroadcastReceivers(intent, GET_META_DATA, null, userId);
            if (resolves.isEmpty()) {
                continue;
            }
            if (!anyDelivered) {
                mBroadcastManager.sendBroadcast(pendingResultData);
                anyDelivered = true;
            }
            deliverBroadcastToResolves(intent, pendingResultData, resolves, userId);
        }
        if (!anyDelivered) {
            pendingResultData.build().finish();
            Slog.d(TAG, "scheduleBroadcastReceiverAllUsers empty: " + intent.getAction());
        }
    }

    @Override
    public void finishBroadcast(PendingResultData data) throws RemoteException {
        mBroadcastManager.finishBroadcast(data);
    }

    @Override
    public String getCallingPackage(IBinder token, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mStack) {
            return userSpace.mStack.getCallingPackage(token, userId);
        }
    }

    @Override
    public ComponentName getCallingActivity(IBinder token, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mStack) {
            return userSpace.mStack.getCallingActivity(token, userId);
        }
    }

    @Override
    public void getIntentSender(IBinder target, String packageName, int uid, int userId) {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mIntentSenderRecords) {
            PendingIntentRecord record = new PendingIntentRecord();
            record.uid = uid;
            record.packageName = packageName;
            userSpace.mIntentSenderRecords.put(target, record);
        }
    }

    @Override
    public String getPackageForIntentSender(IBinder target, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mIntentSenderRecords) {
            PendingIntentRecord record = userSpace.mIntentSenderRecords.get(target);
            if (record != null) {
                return record.packageName;
            }
        }
        return null;
    }

    @Override
    public int getUidForIntentSender(IBinder target, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mIntentSenderRecords) {
            PendingIntentRecord record = userSpace.mIntentSenderRecords.get(target);
            if (record != null) {
                return record.uid;
            }
        }
        return -1;
    }

    @Override
    public void onStartCommand(Intent intent, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            userSpace.mActiveServices.onStartCommand(intent, userId);
        }
    }

    @Override
    public UnbindRecord onServiceUnbind(Intent proxyIntent, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            return userSpace.mActiveServices.onServiceUnbind(proxyIntent, userId);
        }
    }

    @Override
    public void onServiceDestroy(Intent proxyIntent, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            userSpace.mActiveServices.onServiceDestroy(proxyIntent, userId);
        }
    }

    @Override
    public int stopService(Intent intent, String resolvedType, int userId) {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            return userSpace.mActiveServices.stopService(intent, resolvedType, userId);
        }
    }

    @Override
    public Intent bindService(Intent service, IBinder binder, String resolvedType, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            return userSpace.mActiveServices.bindService(service, binder, resolvedType, userId);
        }
    }

    @Override
    public void unbindService(IBinder binder, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            userSpace.mActiveServices.unbindService(binder, userId);
        }
    }

    @Override
    public void stopServiceToken(ComponentName className, IBinder token, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            userSpace.mActiveServices.stopServiceToken(className, token, userId);
        }
    }

    @Override
    public AppConfig initProcess(String packageName, String processName, int userId) throws RemoteException {
        ProcessRecord processRecord = BProcessManagerService.get().startProcessLocked(packageName, processName, userId, -1, Binder.getCallingPid());
        if (processRecord == null)
            return null;
        return processRecord.getClientConfig();
    }

    @Override
    public void restartProcess(String packageName, String processName, int userId) throws RemoteException {
        BProcessManagerService.get().restartAppProcess(packageName, processName, userId);
    }

    @Override
    public void startActivity(Intent intent, int userId) {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mStack) {
            userSpace.mStack.startActivityLocked(userId, intent, null, null, null, -1, -1, null);
        }
    }

    @Override
    public int startActivityAms(int userId, Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode, int flags, Bundle options) throws RemoteException {
        UserSpace space = getOrCreateSpaceLocked(userId);
        synchronized (space.mStack) {
            return space.mStack.startActivityLocked(userId, intent, resolvedType, resultTo, resultWho, requestCode, flags, options);
        }
    }

    @Override
    public int startActivities(int userId, Intent[] intent, String[] resolvedType, IBinder resultTo, Bundle options) throws RemoteException {
        UserSpace space = getOrCreateSpaceLocked(userId);
        synchronized (space.mStack) {
            return space.mStack.startActivitiesLocked(userId, intent, resolvedType, resultTo, options);
        }
    }

    private UserSpace getOrCreateSpaceLocked(int userId) {
        synchronized (mUserSpace) {
            UserSpace userSpace = mUserSpace.get(userId);
            if (userSpace != null)
                return userSpace;
            userSpace = new UserSpace();
            mUserSpace.put(userId, userSpace);
            return userSpace;
        }
    }

    @Override
    public void systemReady() {
        mBroadcastManager.startup();
    }
}
