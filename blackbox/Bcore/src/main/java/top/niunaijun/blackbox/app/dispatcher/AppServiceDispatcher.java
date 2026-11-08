package top.niunaijun.blackbox.app.dispatcher;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;

import java.util.HashMap;
import java.util.Map;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.entity.ServiceRecord;
import top.niunaijun.blackbox.entity.UnbindRecord;
import top.niunaijun.blackbox.proxy.record.ProxyServiceRecord;

import static android.app.Service.START_NOT_STICKY;



public class AppServiceDispatcher {
    public static final String TAG = "AppServiceDispatcher";

    private static final AppServiceDispatcher sServiceDispatcher = new AppServiceDispatcher();

    private Map<Intent.FilterComparison, ServiceRecord> mService = new HashMap<>();

    public static AppServiceDispatcher get() {
        return sServiceDispatcher;
    }

    private final Handler mHandler = BlackBoxCore.get().getHandler();

    public IBinder onBind(Intent proxyIntent) {
        ProxyServiceRecord serviceRecord = ProxyServiceRecord.create(proxyIntent);
        Intent intent = serviceRecord.mServiceIntent;
        ServiceInfo serviceInfo = serviceRecord.mServiceInfo;

        if (intent == null || serviceInfo == null)
            return null;



        Service service = getOrCreateService(serviceRecord);
        if (service == null)
            return null;
        intent.setExtrasClassLoader(service.getClassLoader());

        ServiceRecord record = findRecord(intent);
        record.incrementAndGetBindCount(intent);
        if (record.hasBinder(intent)) {
            if (record.isRebind()) {
                service.onRebind(intent);
                record.setRebind(false);
            }
            return record.getBinder(intent);
        }

        try {
            IBinder iBinder = service.onBind(intent);
            iBinder = wrapChromiumChildBinder(iBinder, serviceInfo);
            record.addBinder(intent, iBinder);
            return iBinder;
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return null;
    }

    // v1.0.82 Fix 1 (Binder-boundary): Chromium child service-এর binder-কে মুড়ে
    // shared RELRO আটকাও। Chromium M156 AIDL (IChildProcessService):
    //   কোড 1 bindToCaller, 2 getSourceDir (sync — ফরওয়ার্ড করতেই হবে),
    //   কোড 3 setupConnection (oneway), 7 consumeRelroLibInfo (oneway)।
    // RELRO দুটো চ্যানেলে আসে: setupConnection-এর args.relroInfo (১০ নম্বর
    // ফিল্ড) আর consumeRelroLibInfo। দুটোই নিরপেক্ষ করো। M156-এ
    // finishLibraryLoad() বলে কিছু নেই, তাই consumeRelroLibInfo ফেলে দিলে
    // child আটকাবে না। setupConnection ফরওয়ার্ড করতেই হবে
    // (commandLine/FD/cpuCount দেয়)। load-address hint bind-Intent extra-তে
    // যায় — v1.0.73-র linker-extra strip সেটা আগেই সরায়।
    private static final String CHILD_SERVICE_DESCRIPTOR =
            "org.chromium.base.process_launcher.IChildProcessService";
    private static final int TRANSACTION_setupConnection = 3;
    private static final int TRANSACTION_consumeRelroLibInfo = 7;

    private static IBinder wrapChromiumChildBinder(IBinder binder, ServiceInfo serviceInfo) {
        if (binder == null || serviceInfo == null || serviceInfo.name == null
                || serviceInfo.packageName == null) {
            return binder;
        }
        boolean isChromiumChild = serviceInfo.name.contains("ProcessService")
                && (serviceInfo.packageName.contains("chrome")
                || serviceInfo.packageName.contains("brave"));
        if (!isChromiumChild) return binder;
        BlackBoxCore.traceInitPublic(BlackBoxCore.getContext(),
                "v1.0.82 binder wrapped for " + serviceInfo.name);
        return new ChromiumRelroBlocker(binder);
    }

    private static class ChromiumRelroBlocker extends Binder {
        private final IBinder mReal;

        ChromiumRelroBlocker(IBinder real) {
            mReal = real;
        }

        @Override
        public String getInterfaceDescriptor() {
            return CHILD_SERVICE_DESCRIPTOR;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == TRANSACTION_consumeRelroLibInfo) {
                // RELRO ফেলে দাও — M156-এ কোনো wait নেই, আটকাবে না।
                BlackBoxCore.traceInitPublic(BlackBoxCore.getContext(),
                        "v1.0.82 RELRO blocked: consumeRelroLibInfo dropped");
                return true;
            }
            if (code == TRANSACTION_setupConnection) {
                try {
                    return forwardSetupConnectionNulled(data, reply, flags);
                } catch (Throwable t) {
                    BlackBoxCore.traceInitPublic(BlackBoxCore.getContext(),
                            "v1.0.82 setupConnection rewrite failed: " + t
                            + " — forwarding original");
                    try {
                        data.setDataPosition(0);
                    } catch (Throwable ignored) { }
                    return mReal.transact(code, data, reply, flags);
                }
            }
            return mReal.transact(code, data, reply, flags);
        }

        private boolean forwardSetupConnectionNulled(Parcel data, Parcel reply, int flags)
                throws Exception {
            data.enforceInterface(CHILD_SERVICE_DESCRIPTOR);
            // IChildProcessArgs পড়ো (CREATOR দিয়ে — ক্লাসটা child প্রসেসের
            // loader-এ আছে, কারণ Stub নিজেই এটা দিয়ে parcel পড়ে)।
            Class<?> argsClass = Class.forName(
                    "org.chromium.base.process_launcher.IChildProcessArgs");
            Parcelable.Creator<?> creator = (Parcelable.Creator<?>)
                    argsClass.getField("CREATOR").get(null);
            Object args = creator.createFromParcel(data);
            IBinder parentBinder = data.readStrongBinder();
            java.util.ArrayList<IBinder> clientInterfaces = data.createBinderArrayList();
            // relroInfo = null করো।
            java.lang.reflect.Field relroField = argsClass.getField("relroInfo");
            relroField.setAccessible(true);
            Object oldRelro = relroField.get(args);
            relroField.set(args, null);
            // নতুন parcel বানিয়ে আসল binder-এ পাঠাও।
            Parcel newData = Parcel.obtain();
            try {
                newData.writeInterfaceToken(CHILD_SERVICE_DESCRIPTOR);
                newData.writeParcelable((Parcelable) args, 0);
                newData.writeStrongBinder(parentBinder);
                newData.writeBinderList(clientInterfaces);
                BlackBoxCore.traceInitPublic(BlackBoxCore.getContext(),
                        "v1.0.82 setupConnection relroInfo nulled (was "
                        + (oldRelro != null) + ")");
                return mReal.transact(TRANSACTION_setupConnection, newData, reply, flags);
            } finally {
                newData.recycle();
            }
        }
    }

    public int onStartCommand(Intent proxyIntent, int flags, int startId) {
        ProxyServiceRecord stubRecord = ProxyServiceRecord.create(proxyIntent);
        if (stubRecord.mServiceIntent == null || stubRecord.mServiceInfo == null) {
            return START_NOT_STICKY;
        }


        Service service = getOrCreateService(stubRecord);
        if (service == null)
            return START_NOT_STICKY;
        stubRecord.mServiceIntent.setExtrasClassLoader(service.getClassLoader());
        ServiceRecord record = findRecord(stubRecord.mServiceIntent);
        record.setStartId(stubRecord.mStartId);
        try {
            int i = service.onStartCommand(stubRecord.mServiceIntent, flags, stubRecord.mStartId);
            BlackBoxCore.getBActivityManager().onStartCommand(proxyIntent, stubRecord.mUserId);
            return i;
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return START_NOT_STICKY;
    }

    public void onDestroy() {
        if (mService.size() > 0) {
            java.util.Iterator<java.util.Map.Entry<Intent.FilterComparison, ServiceRecord>> it =
                    mService.entrySet().iterator();
            while (it.hasNext()) {
                java.util.Map.Entry<Intent.FilterComparison, ServiceRecord> entry = it.next();
                Service svc = entry.getValue().getService();
                String svcName = svc != null ? svc.getClass().getName() : null;
                boolean isChromiumChild = svcName != null
                        && svcName.contains("ProcessService") && svcName.contains("chromium");
                // Chromium child service — onDestroy() ডেকো না (System.exit(0)
                // করে renderer মেরে দেয়)। cache-এ রেখে দাও, re-bind-এ reuse হবে।
                if (isChromiumChild) {
                    BlackBoxCore.traceInitPublic(BlackBoxCore.getContext(),
                            "onDestroy chromium child kept alive: " + svcName);
                    continue;
                }
                try {
                    entry.getValue().getService().onDestroy();
                } catch (Throwable e) {
                    e.printStackTrace();
                }
                it.remove();
            }
        }

    }

    public void onConfigurationChanged(Configuration newConfig) {
        if (mService.size() > 0) {
            for (ServiceRecord record : mService.values()) {
                try {
                    record.getService().onConfigurationChanged(newConfig);
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        }

    }

    public void onLowMemory() {
        if (mService.size() > 0) {
            for (ServiceRecord record : mService.values()) {
                try {
                    record.getService().onLowMemory();
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        }

    }

    public void onTrimMemory(int level) {
        if (mService.size() > 0) {
            for (ServiceRecord record : mService.values()) {
                try {
                    record.getService().onTrimMemory(level);
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        }
        
    }

    public boolean onUnbind(Intent proxyIntent) {
        ProxyServiceRecord stubRecord = ProxyServiceRecord.create(proxyIntent);
        if (stubRecord.mServiceIntent == null || stubRecord.mServiceInfo == null) {
            return false;
        }
        Intent intent = stubRecord.mServiceIntent;

        try {
            UnbindRecord unbindRecord = BlackBoxCore.getBActivityManager().onServiceUnbind(proxyIntent, BlackBoxCore.getUserId());
            if (unbindRecord == null)
                return false;

            Service service = getOrCreateService(stubRecord);
            if (service == null)
                return false;

            stubRecord.mServiceIntent.setExtrasClassLoader(service.getClassLoader());

            ServiceRecord record = findRecord(intent);

            boolean destroy = unbindRecord.getStartId() == 0;
            // Chromium child service (renderer/GPU/privileged) — unbind-এ
            // destroy কোরো না: ChildProcessService.onDestroy() System.exit(0)
            // ডাকে, আর Chrome নেভিগেশন/রিলোডে transient unbind করে।
            // destroy করলে renderer মরে "Aw, Snap!" আসে — 15:04:06-এর
            // clean exit (result code 0) ঠিক এই পথেই হয়েছে। Service-টা
            // cache-এ রেখে দাও, re-bind-এ onRebind() দিয়ে একই binder
            // ফেরত যাবে।
            ServiceInfo chromiumInfo = stubRecord.mServiceInfo;
            boolean isChromiumChild = chromiumInfo != null && chromiumInfo.name != null
                    && chromiumInfo.name.contains("ProcessService")
                    && chromiumInfo.packageName != null
                    && (chromiumInfo.packageName.contains("chrome") || chromiumInfo.packageName.contains("brave"));
            if (isChromiumChild) {
                destroy = false;
                BlackBoxCore.traceInitPublic(BlackBoxCore.getContext(),
                        "onUnbind chromium child kept alive: " + chromiumInfo.name);
            }
            if (destroy || record.decreaseConnectionCount(intent)) {
                boolean b = service.onUnbind(intent);
                if (destroy) {
                    service.onDestroy();
                    BlackBoxCore.getBActivityManager().onServiceDestroy(proxyIntent, BlackBoxCore.getUserId());
                    mService.remove(new Intent.FilterComparison(intent));
                }
                record.setRebind(true);

            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return false;
    }

    public IBinder peekService(Intent intent) {
        ServiceRecord record = findRecord(intent);
        if (record == null) {
            return null;
        }
        return record.getBinder(intent);
    }

    public void stopService(Intent intent) {
        if (intent == null)
            return;
        ServiceRecord record = findRecord(intent);
        if (record == null)
            return;
        if (record.getService() != null) {
            boolean destroy = record.getStartId() > 0;
            try {
                if (destroy) {
                    mHandler.post(() -> record.getService().onDestroy());
                    BlackBoxCore.getBActivityManager().onServiceDestroy(intent, BlackBoxCore.getUserId());
                    mService.remove(new Intent.FilterComparison(intent));
                }
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    private ServiceRecord findRecord(Intent intent) {
        return mService.get(new Intent.FilterComparison(intent));
    }

    private Service getOrCreateService(ProxyServiceRecord proxyServiceRecord) {
        Intent intent = proxyServiceRecord.mServiceIntent;
        ServiceInfo serviceInfo = proxyServiceRecord.mServiceInfo;
        IBinder token = proxyServiceRecord.mToken;

        ServiceRecord record = findRecord(intent);
        if (record != null && record.getService() != null) {
            return record.getService();
        }
        Service service = BlackBoxCore.currentActivityThread().createService(serviceInfo, token);
        if (service == null)
            return null;
        record = new ServiceRecord();
        record.setService(service);
        mService.put(new Intent.FilterComparison(intent), record);
        return service;
    }
}
