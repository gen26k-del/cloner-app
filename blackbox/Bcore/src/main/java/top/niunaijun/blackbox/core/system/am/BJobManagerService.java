package top.niunaijun.blackbox.core.system.am;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.RemoteException;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import black.android.app.job.BRJobInfo;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.system.BProcessManagerService;
import top.niunaijun.blackbox.core.system.ISystemService;
import top.niunaijun.blackbox.core.system.ProcessRecord;
import top.niunaijun.blackbox.core.system.pm.BPackageManagerService;
import top.niunaijun.blackbox.entity.JobRecord;
import top.niunaijun.blackbox.proxy.ProxyJobService;
import top.niunaijun.blackbox.proxy.ProxyManifest;


public class BJobManagerService extends IBJobManagerService.Stub implements ISystemService {
    private static final BJobManagerService sService = new BJobManagerService();

    // v1.0.166: proxy job-এর ঊর্ধ্বসীমা (DualSpaceLivre থেকে হুবহু,
    // তাদের ডিভাইস-যাচাইকৃত মান)। সব guest একই host UID ভাগ করে —
    // guest job-গুলো Android-এর 100-jobs-per-UID সীমার বিপরীতে
    // গোনা হয়; সীমা ছাড়ালে তাদের হোস্ট crash-loop-এ পড়েছিল এবং
    // engine ANR হতো। 64-তে থামালে host-এর নিজের WorkManager-এর
    // জন্য জায়গা থাকে। সীমা পেরোলে scheduleJob null ফেরত দেয়,
    // guest পক্ষ (IJobServiceProxy) তা RESULT_FAILURE বানায়।
    private static final int MAX_PROXY_JOBS = 64;

    private final Map<String, JobRecord> mJobRecords = new LinkedHashMap<>();

    public static BJobManagerService get() {
        return sService;
    }

    @Override
    public JobInfo schedule(JobInfo info, int userId) throws RemoteException {
        ComponentName componentName = info.getService();
        Intent intent = new Intent();
        intent.setComponent(componentName);
        ResolveInfo resolveInfo = BPackageManagerService.get().resolveService(intent, PackageManager.GET_META_DATA, null, userId);
        if (resolveInfo == null) {
            return info;
        }
        ServiceInfo serviceInfo = resolveInfo.serviceInfo;
        ProcessRecord processRecord = BProcessManagerService.get().findProcessRecord(serviceInfo.packageName, serviceInfo.processName, userId);
        if (processRecord == null) {
            processRecord = BProcessManagerService.get().
                    startProcessLocked(serviceInfo.packageName, serviceInfo.processName, userId, -1, Binder.getCallingPid());
            if (processRecord == null) {
                throw new RuntimeException(
                        "Unable to create Process " + serviceInfo.processName);
            }
        }
        return scheduleJob(processRecord, info, serviceInfo, userId);
    }

    @Override
    public JobRecord queryJobRecord(String processName, int jobId, int userId) throws RemoteException {
        synchronized (mJobRecords) {
            return mJobRecords.get(formatKey(userId, processName, jobId));
        }
    }

    public JobInfo scheduleJob(ProcessRecord processRecord, JobInfo info, ServiceInfo serviceInfo, int userId) {
        JobRecord jobRecord = new JobRecord();
        jobRecord.mJobInfo = info;
        jobRecord.mServiceInfo = serviceInfo;

        // v1.0.166: key-তে userId-ও (DualSpaceLivre) — একই process
        // নামের আলাদা user-এর job পরস্পরকে মুছে ফেলতে পারে না।
        String key = formatKey(userId, processRecord.processName, info.getId());
        synchronized (mJobRecords) {
            if (!mJobRecords.containsKey(key) && mJobRecords.size() >= MAX_PROXY_JOBS) {
                try {
                    BlackBoxCore.traceInitPublic(BlackBoxCore.getContext(),
                            "JobCap: rejected jobId=" + info.getId()
                                    + " proc=" + processRecord.processName
                                    + " user=" + userId
                                    + " live=" + mJobRecords.size());
                } catch (Throwable ignored) { }
                return null;
            }
            mJobRecords.put(key, jobRecord);
        }
        BRJobInfo.get(info)._set_service(new ComponentName(BlackBoxCore.getHostPkg(), ProxyManifest.getProxyJobService(processRecord.bpid)));
        return info;
    }

    @Override
    public void cancelAll(String processName, int userId) throws RemoteException {
        if (TextUtils.isEmpty(processName)) return;
        // v1.0.166: আগের সংস্করণ record সরাতই না (খালি loop) — এখন
        // আসল JobScheduler-এ cancel + record অপসারণ (DualSpaceLivre)।
        String prefix = userId + "_" + processName + "_";
        JobScheduler scheduler = getJobScheduler();
        synchronized (mJobRecords) {
            Iterator<Map.Entry<String, JobRecord>> iterator = mJobRecords.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, JobRecord> entry = iterator.next();
                if (entry.getKey().startsWith(prefix)) {
                    if (scheduler != null && entry.getValue().mJobInfo != null) {
                        scheduler.cancel(entry.getValue().mJobInfo.getId());
                    }
                    iterator.remove();
                }
            }
        }
    }

    @Override
    public int cancel(String processName, int jobId, int userId) throws RemoteException {
        // v1.0.166: আগের সংস্করণ record সরাতই না — cap-এর হিসাব
        // চিরকাল বাড়তই থাকত। এখন সরাও (DualSpaceLivre)।
        synchronized (mJobRecords) {
            mJobRecords.remove(formatKey(userId, processName, jobId));
        }
        return jobId;
    }

    private String formatKey(int userId, String processName, int jobId) {
        return userId + "_" + processName + "_" + jobId;
    }

    private JobScheduler getJobScheduler() {
        return (JobScheduler) BlackBoxCore.getContext().getSystemService(Context.JOB_SCHEDULER_SERVICE);
    }

    @Override
    public void systemReady() {
        // v1.0.166: :black পুনরায় চালু হলে আগের জীবনের ProxyJobService
        // job-গুলো আসল JobScheduler-এ ঝুলে থাকে, অথচ তাদের in-memory
        // record নেই — সেগুলো stale, বাতিল করো (DualSpaceLivre)।
        JobScheduler scheduler = getJobScheduler();
        if (scheduler == null) return;
        for (JobInfo pendingJob : new ArrayList<>(scheduler.getAllPendingJobs())) {
            ComponentName service = pendingJob.getService();
            if (service != null
                    && BlackBoxCore.getHostPkg().equals(service.getPackageName())
                    && service.getClassName().startsWith(ProxyJobService.class.getName())) {
                scheduler.cancel(pendingJob.getId());
            }
        }
        synchronized (mJobRecords) {
            mJobRecords.clear();
        }
    }
}
