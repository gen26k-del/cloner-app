package top.niunaijun.blackbox.core.system;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.IBActivityThread;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.system.notification.BNotificationManagerService;
import top.niunaijun.blackbox.core.system.pm.BPackageManagerService;
import top.niunaijun.blackbox.core.system.user.BUserHandle;
import top.niunaijun.blackbox.entity.AppConfig;
import top.niunaijun.blackbox.proxy.ProxyManifest;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.ApplicationThreadCompat;
import top.niunaijun.blackbox.utils.compat.BundleCompat;
import top.niunaijun.blackbox.utils.provider.ProviderCall;


public class BProcessManagerService implements ISystemService {
    public static final String TAG = "BProcessManager";

    public static BProcessManagerService sBProcessManagerService = new BProcessManagerService();
    private final Map<Integer, Map<String, ProcessRecord>> mProcessMap = new HashMap<>();
    private final List<ProcessRecord> mPidsSelfLocked = new ArrayList<>();
    private final Object mProcessLock = new Object();

    public static BProcessManagerService get() {
        return sBProcessManagerService;
    }

    public ProcessRecord startProcessLocked(String packageName, String processName, int userId, int bpid, int callingPid) {
        return startProcessLocked(packageName, processName, userId, bpid, callingPid, false);
    }

    private ProcessRecord startProcessLocked(String packageName, String processName, int userId, int bpid, int callingPid, boolean waiterRetryUsed) {
        ApplicationInfo info = BPackageManagerService.get().getApplicationInfo(packageName, 0, userId);
        if (info == null)
            return null;
        ProcessRecord app;
        int buid = BUserHandle.getUid(userId, BPackageManagerService.get().getAppId(packageName));
        // v1.0.136: initAppProcessL (provider call, ৩ retry × ৫ সেকেন্ড
        // পর্যন্ত) আগে mProcessLock ধরে চলতো — ওই সময় onProcessDie আটকে
        // থাকতো, মরা রেকর্ড জমতো, আর সব bind সিরিয়াল হতো (bind ব্যর্থ →
        // Chromium নতুন child বানায় → orphan _exit মৃত্যুর churn)। এখন
        // লক শুধু রিজার্ভ/যাচাইয়ের জন্য; init লকের বাইরে। bpid রিজার্ভ
        // নিরাপদ কারণ getUsingBPidL (v1.0.135) ইতিমধ্যে বরাদ্দ রেকর্ডও গোনে।
        ProcessRecord waitingApp = null;
        boolean fresh = false;
        synchronized (mProcessLock) {
            Map<String, ProcessRecord> bProcess = mProcessMap.get(buid);
            if (bProcess == null) {
                bProcess = new HashMap<>();
            }
            if (bpid == -1) {
                app = bProcess.get(processName);
                if (app != null) {
                    if (app.bActivityThread != null) {
                        // প্রসেস মরে গেলেও onProcessDie সময়মতো না চললে পুরনো
                        // রেকর্ড থেকে যেতে পারে — মরা binder ফেরত দিলে bind
                        // আটকে Chrome ক্র্যাশ করে। binder বেঁচে আছে কিনা
                        // যাচাই করো; মরা হলে রেকর্ড সরিয়ে নতুন প্রসেস বানাও।
                        boolean alive = false;
                        try {
                            alive = app.bActivityThread.asBinder().isBinderAlive();
                        } catch (Exception ignored) {
                        }
                        if (alive) {
                            return app;
                        }
                        bProcess.remove(processName);
                        mPidsSelfLocked.remove(app);
                        app = null;
                    } else {
                        // init চলছে — লক ছেড়ে init শেষ হওয়া পর্যন্ত অপেক্ষা করো।
                        waitingApp = app;
                        app = null;
                    }
                }
                if (waitingApp == null) {
                    int freshBpid = getUsingBPidL();
                    if (freshBpid == -1) {
                        throw new RuntimeException("No processes available");
                    }
                    Slog.d(TAG, "init bUid = " + buid + ", bPid = " + freshBpid);
                    app = new ProcessRecord(info, processName);
                    app.uid = Process.myUid();
                    app.bpid = freshBpid;
                    app.buid = BPackageManagerService.get().getAppId(packageName);
                    app.callingBUid = getBUidByPidOrPackageName(callingPid, packageName);
                    app.userId = userId;
                    bProcess.put(processName, app);
                    mPidsSelfLocked.add(app);
                    synchronized (mProcessMap) {
                        mProcessMap.put(buid, bProcess);
                    }
                    fresh = true;
                }
            } else {
                // restart পথ: নির্দিষ্ট bpid-এর নতুন রেকর্ড।
                app = new ProcessRecord(info, processName);
                app.uid = Process.myUid();
                app.bpid = bpid;
                app.buid = BPackageManagerService.get().getAppId(packageName);
                app.callingBUid = getBUidByPidOrPackageName(callingPid, packageName);
                app.userId = userId;
                bProcess.put(processName, app);
                mPidsSelfLocked.add(app);
                synchronized (mProcessMap) {
                    mProcessMap.put(buid, bProcess);
                }
                fresh = true;
            }
        }
        // লকের বাইরে: চলমান init-এর জন্য অপেক্ষা, অথবা নতুন init।
        if (waitingApp != null) {
            if (waitingApp.initLock != null) {
                waitingApp.initLock.block();
            }
            synchronized (mProcessLock) {
                Map<String, ProcessRecord> bProcess = mProcessMap.get(buid);
                ProcessRecord current = bProcess == null ? null : bProcess.get(processName);
                if (current == waitingApp && waitingApp.bActivityThread != null) {
                    try {
                        if (waitingApp.bActivityThread.asBinder().isBinderAlive()) {
                            return waitingApp;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            // v1.0.140: অপেক্ষা করা init ব্যর্থ/মরা — v1.0.136-এর পর এখানে
            // সরাসরি null ফিরত, ফলে একবারের ব্যর্থতা ওই প্রসেসের জন্য
            // স্থায়ী হয়ে যেত। একবার নতুন করে শুরু করার চেষ্টা করো
            // (waiterRetryUsed পতাকা দিয়ে একবারেই সীমাবদ্ধ — অসীম চক্র নয়)।
            if (!waiterRetryUsed) {
                return startProcessLocked(packageName, processName, userId, -1, callingPid, true);
            }
            return null;
        }
        if (fresh) {
            boolean initOk;
            try {
                initOk = initAppProcessL(app);
            } catch (Throwable t) {
                // v1.0.140: initAppProcessL exception ছুড়লে আগের কোডে
                // exception-টা সোজা বেরিয়ে যেত — রেকর্ডটা initLock বন্ধ
                // অবস্থায় map-এ থেকে যেত (poisoned record), ফলে ওই
                // প্রসেসের পরের সব launch initLock-এ চিরকাল আটকে থাকত।
                // ব্যর্থতা হিসেবে ধরে নিচের পরিষ্কার-পথে পাঠাও (initLock
                // খোলা + রেকর্ড সরানো)।
                Log.e(TAG, "initAppProcessL threw for " + processName, t);
                initOk = false;
            }
            if (!initOk) {
                synchronized (mProcessLock) {
                    // অপেক্ষমাণ thread যাতে initLock-এ চিরকাল আটকে না থাকে।
                    if (app.initLock != null) {
                        app.initLock.open();
                    }
                    Map<String, ProcessRecord> bProcess = mProcessMap.get(buid);
                    if (bProcess != null && bProcess.get(processName) == app) {
                        bProcess.remove(processName);
                    }
                    mPidsSelfLocked.remove(app);
                }
                return null;
            }
            app.pid = getPid(BlackBoxCore.getContext(), ProxyManifest.getProcessName(app.bpid));
            synchronized (mProcessLock) {
                // init চলাকালীন প্রসেস মরে গেলে onProcessDie রেকর্ড সরিয়ে
                // ফেলতে পারে — তখন মরা রেকর্ড ফেরত দিও না।
                Map<String, ProcessRecord> bProcess = mProcessMap.get(buid);
                if (bProcess == null || bProcess.get(processName) != app) {
                    return null;
                }
            }
            return app;
        }
        return null;
    }

    private int getUsingBPidL() {
        ActivityManager manager = (ActivityManager) BlackBoxCore.getContext().getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> runningAppProcesses = manager.getRunningAppProcesses();
        Set<Integer> usingPs = new HashSet<>();
        if (runningAppProcesses != null) {
            for (ActivityManager.RunningAppProcessInfo runningAppProcess : runningAppProcesses) {
                int i = parseBPid(runningAppProcess.processName);
                usingPs.add(i);
            }
        }
        // v1.0.135: also reserve bpids already allocated to records whose
        // processes have not started yet (closes the double-allocation
        // window; every mutation of mPidsSelfLocked holds mProcessLock,
        // which the caller holds here).
        for (ProcessRecord record : mPidsSelfLocked) {
            usingPs.add(record.bpid);
        }
        for (int i = 0; i < ProxyManifest.FREE_COUNT; i++) {
            if (usingPs.contains(i)) {
                continue;
            }
            return i;
        }
        return -1;
    }

    public void restartAppProcess(String packageName, String processName, int userId) {
        // v1.0.136: mProcessLock ধরে startProcessLocked ডেকো না — লক এখন
        // শুধু রিজার্ভ/যাচাইয়ের জন্য, init লকের বাইরে চলে। findProcessByPid
        // mPidsSelfLocked-এর নিজের লক ব্যবহার করে, mProcessLock লাগে না।
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();
        ProcessRecord app = findProcessByPid(callingPid);
        if (app == null) {
            String stubProcessName = getProcessName(BlackBoxCore.getContext(), callingPid);
            int bpid = parseBPid(stubProcessName);
            startProcessLocked(packageName, processName, userId, bpid, callingPid);
        }
    }

    private int parseBPid(String stubProcessName) {
        String prefix;
        if (stubProcessName == null) {
            return -1;
        } else {
            prefix = BlackBoxCore.getHostPkg() + ":p";
        }
        if (stubProcessName.startsWith(prefix)) {
            try {
                return Integer.parseInt(stubProcessName.substring(prefix.length()));
            } catch (NumberFormatException e) {
                
            }
        }
        return -1;
    }

    private boolean initAppProcessL(ProcessRecord record) {
        Log.d(TAG, "initProcess: " + record.processName);
        AppConfig appConfig = record.getClientConfig();
        Bundle bundle = new Bundle();
        bundle.putParcelable(AppConfig.KEY, appConfig);
        // Cold stub প্রসেস (বিশেষ করে Chrome-এর renderer/GPU child) চালু হতে
        // ২ সেকেন্ডের বেশি লাগতে পারে — provider call একবারে ব্যর্থ হলে সাথে
        // সাথে false না দিয়ে ৩ বার retry করো, নইলে bindService আসল intent-এ
        // ফলব্যাক করে আর renderer মরে ব্ল্যাক স্ক্রিন থাকে।
        IBinder appThread = null;
        for (int attempt = 0; attempt < 3 && (appThread == null || !appThread.isBinderAlive()); attempt++) {
            if (attempt > 0) android.os.SystemClock.sleep(500);
            Bundle init = ProviderCall.callSafely(record.getProviderAuthority(), "_Black_|_init_process_", null, bundle);
            if (init != null) {
                appThread = BundleCompat.getBinder(init, "_Black_|_client_");
            }
        }
        if (appThread == null || !appThread.isBinderAlive()) {
            return false;
        }
        attachClientL(record, appThread);

        createProc(record);
        return true;
    }

    private void attachClientL(final ProcessRecord app, final IBinder appThread) {
        IBActivityThread activityThread = IBActivityThread.Stub.asInterface(appThread);
        if (activityThread == null) {
            app.kill();
            return;
        }
        try {
            appThread.linkToDeath(new IBinder.DeathRecipient() {
                @Override
                public void binderDied() {
                    Log.d(TAG, "App Died: " + app.processName);
                    appThread.unlinkToDeath(this, 0);
                    onProcessDie(app);
                }
            }, 0);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        app.bActivityThread = activityThread;
        try {
            app.appThread = ApplicationThreadCompat.asInterface(activityThread.getActivityThread());
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        app.initLock.open();
    }

    public void onProcessDie(ProcessRecord record) {
        // v1.0.136: init চলাকালীন প্রসেস মরলে অপেক্ষমাণ thread যাতে
        // initLock-এ চিরকাল আটকে না থাকে (ConditionVariable.open idempotent)।
        if (record.initLock != null) {
            record.initLock.open();
        }
        // Chromium child প্রসেস মরলে ট্রেসে লেখো — "Aw, Snap!"-এর সময়
        // renderer কখন মরল, সেটা স্মার্ট রিপোর্টের ট্রেসেই দেখা যাবে
        // (logcat ছাড়া)। মৃত্যুর কারণ (নেটিভ ক্র্যাশ নাকি clean exit)
        // এতে ধরা পড়বে না, শুধু সময়টা ধরা পড়বে।
        String diePkg = record.getPackageName();
        if (diePkg != null && (diePkg.contains("chrome") || diePkg.contains("brave"))
                && record.processName != null && record.processName.contains(":")) {
            BlackBoxCore.traceInitPublic(BlackBoxCore.getContext(),
                    "child process died: " + record.processName);
        }
        // v1.0.167: every guest process death is traced - the
        // residency evidence for the notification work. A guest's
        // own MQTT/socket connection is the only push route a clone
        // has, so the report must show how long each guest lives.
        try {
            BlackBoxCore.traceInitPublic(BlackBoxCore.getContext(),
                    "GuestDeath: pkg=" + record.getPackageName()
                            + " proc=" + record.processName
                            + " pid=" + record.pid + " user=" + record.userId);
        } catch (Throwable ignored) { }
        synchronized (mProcessLock) {
            record.kill();
            Map<String, ProcessRecord> process = mProcessMap.get(record.buid);
            if (process != null) {
                process.remove(record.processName);
                if (process.isEmpty()) {
                    mProcessMap.remove(record.buid);
                }
            }
            mPidsSelfLocked.remove(record);

            removeProc(record);
            // v1.0.167: process death no longer wipes the guest's
            // notifications. Up to .166 this called
            // deletePackageNotification (its ONLY caller), so every
            // guest death - LMK included - cancelled that guest's
            // notifications and deleted its channels, which real
            // Android never does: a notification outlives its
            // poster's process. Cleanup now happens on uninstall
            // (BPackageManagerService), its proper home.
        }
    }

    public ProcessRecord findProcessRecord(String packageName, String processName, int userId) {
        synchronized (mProcessMap) {
            int appId = BPackageManagerService.get().getAppId(packageName);
            int buid = BUserHandle.getUid(userId, appId);
            Map<String, ProcessRecord> processRecordMap = mProcessMap.get(buid);
            if (processRecordMap == null)
                return null;
            return processRecordMap.get(processName);
        }
    }

    public void killAllByPackageName(String packageName) {
        synchronized (mProcessLock) {
            synchronized (mPidsSelfLocked) {
                List<ProcessRecord> tmp = new ArrayList<>(mPidsSelfLocked);
                int appId = BPackageManagerService.get().getAppId(packageName);
                for (ProcessRecord processRecord : mPidsSelfLocked) {
                    int appId1 = BUserHandle.getAppId(processRecord.buid);
                    if (appId == appId1) {
                        mProcessMap.remove(processRecord.buid);
                        tmp.remove(processRecord);
                        processRecord.kill();
                    }
                }
                mPidsSelfLocked.clear();
                mPidsSelfLocked.addAll(tmp);
            }
        }
    }

    public void killPackageAsUser(String packageName, int userId) {
        synchronized (mProcessLock) {
            int buid = BUserHandle.getUid(userId, BPackageManagerService.get().getAppId(packageName));
            Map<String, ProcessRecord> process = mProcessMap.get(buid);
            if (process == null)
                return;
            for (ProcessRecord value : process.values()) {
                value.kill();
                mPidsSelfLocked.remove(value);
            }
            mProcessMap.remove(buid);
        }
    }

    public List<ProcessRecord> getPackageProcessAsUser(String packageName, int userId) {
        synchronized (mProcessMap) {
            int buid = BUserHandle.getUid(userId, BPackageManagerService.get().getAppId(packageName));
            Map<String, ProcessRecord> process = mProcessMap.get(buid);
            if (process == null)
                return new ArrayList<>();
            return new ArrayList<>(process.values());
        }
    }

    public int getBUidByPidOrPackageName(int pid, String packageName) {
        ProcessRecord callingProcess = findProcessByPid(pid);
        if (callingProcess == null) {
            return BPackageManagerService.get().getAppId(packageName);
        }
        return BUserHandle.getAppId(callingProcess.buid);
    }

    public int getUserIdByCallingPid(int callingPid) {
        ProcessRecord callingProcess = findProcessByPid(callingPid);
        if (callingProcess == null) {
            return 0;
        }
        return callingProcess.userId;
    }

    public ProcessRecord findProcessByPid(int pid) {
        synchronized (mPidsSelfLocked) {
            for (ProcessRecord processRecord : mPidsSelfLocked) {
                if (processRecord.pid == pid)
                    return processRecord;
            }
            return null;
        }
    }

    private static String getProcessName(Context context, int pid) {
        String processName = null;
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningAppProcessInfo info : am.getRunningAppProcesses()) {
            if (info.pid == pid) {
                processName = info.processName;
                break;
            }
        }
        if (processName == null) {
            throw new RuntimeException("processName = null");
        }
        return processName;
    }

    public static int getPid(Context context, String processName) {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> runningAppProcesses = manager.getRunningAppProcesses();
            for (ActivityManager.RunningAppProcessInfo runningAppProcess : runningAppProcesses) {
                if (runningAppProcess.processName.equals(processName)) {
                    return runningAppProcess.pid;
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return -1;
    }

    private static void createProc(ProcessRecord record) {
        File cmdline = new File(BEnvironment.getProcDir(record.bpid), "cmdline");
        try {
            FileUtils.writeToFile(record.processName.getBytes(), cmdline);
        } catch (IOException ignored) {
        }
    }

    private static void removeProc(ProcessRecord record) {
        FileUtils.deleteDir(BEnvironment.getProcDir(record.bpid));
    }

    @Override
    public void systemReady() {
        FileUtils.deleteDir(BEnvironment.getProcDir());
    }
}
