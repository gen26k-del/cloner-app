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
            // v1.0.83 যাচাই: wrapper-এ আদৌ কোনো transact আসছে কিনা — code 1
            // (bindToCaller) এলে browser renderer-এর সাথে কথা বলছে; code 3/7
            // না এলে child setup-এর আগেই মরছে বা browser bind ছেড়ে দিচ্ছে।
            try {
                BlackBoxCore.traceInitPublic(BlackBoxCore.getContext(),
                        "v1.0.83 transact code=" + code + " flags=" + flags);
            } catch (Throwable ignored) { }
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

        // v1.0.89: TYPE দিয়ে field খোঁজো — type-এর নামে (case-insensitive)
        // part থাকলেই নেবে (R8 field-এর নাম বদলালেও type একই থাকে)।
        private static java.lang.reflect.Field findFieldByTypeName(
                Class<?> cls, String part) throws NoSuchFieldException {
            String want = part.toLowerCase(java.util.Locale.US);
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                if (f.getType().getName().toLowerCase(java.util.Locale.US).contains(want)) {
                    return f;
                }
            }
            throw new NoSuchFieldException(part);
        }

        // v1.0.89: ঠিক type দিয়ে field খোঁজো (যেমন String[].class)।
        private static java.lang.reflect.Field findFieldByType(
                Class<?> cls, Class<?> type) throws NoSuchFieldException {
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                if (type.equals(f.getType())) {
                    return f;
                }
            }
            throw new NoSuchFieldException(type.getName());
        }

        private boolean forwardSetupConnectionNulled(Parcel data, Parcel reply, int flags)
                throws Exception {
            data.enforceInterface(CHILD_SERVICE_DESCRIPTOR);
            // v1.0.86: IChildProcessArgs-কে wrapper-এর loader দিয়ে খোঁজা
            // যায় না — wrapper BlackBox/host loader-এ চলে, Chromium ক্লাস
            // ওখানে নেই (v1.0.85-তে প্রতিবার ClassNotFoundException হয়ে
            // original parcel forward হয়েছে — relro nulling আর --no-sandbox
            // কখনো চলেনি)। আসল binder (mReal) হলো service-এর Stub, virtual
            // app-এর loader-এ লোড হয়েছে — সেই loader দিয়ে খোঁজো; না পেলে
            // context loader, তারপর wrapper-এর নিজের loader।
            Class<?> argsClass = null;
            Throwable lastErr = null;
            ClassLoader[] loaders = new ClassLoader[] {
                    mReal.getClass().getClassLoader(),
                    Thread.currentThread().getContextClassLoader(),
                    ChromiumRelroBlocker.class.getClassLoader()
            };
            for (ClassLoader cl : loaders) {
                if (cl == null) continue;
                try {
                    argsClass = Class.forName(
                            "org.chromium.base.process_launcher.IChildProcessArgs",
                            false, cl);
                    break;
                } catch (Throwable t) { lastErr = t; }
            }
            if (argsClass == null) {
                throw new ClassNotFoundException(
                        "org.chromium.base.process_launcher.IChildProcessArgs",
                        lastErr);
            }
            Parcelable.Creator<?> creator = (Parcelable.Creator<?>)
                    argsClass.getField("CREATOR").get(null);
            // v1.0.87: AIDL proxy args লেখে writeTypedObject দিয়ে — আগে
            // 4-byte marker (1 = non-null) থাকে, তারপর size-prefix সহ
            // parcelable। v1.0.86 সরাসরি createFromParcel ডেকেছিল — marker-এর
            // 1-কে size ভেবে "Parcelable too small" হতো (v1.0.86 রিপোর্টে
            // প্রতিবার)। আসল Stub-ও readTypedObject দিয়েই পড়ে — সেই
            // পথেই পড়ো। args null হলে rewrite করার কিছু নেই।
            Object args = data.readTypedObject(creator);
            if (args == null) {
                throw new IllegalStateException("IChildProcessArgs is null");
            }
            IBinder parentBinder = data.readStrongBinder();
            java.util.ArrayList<IBinder> clientInterfaces = data.createBinderArrayList();
            // relroInfo = null করো।
            // v1.0.89: TYPE দিয়ে field খোঁজো — v1.0.88-তে নাম দিয়ে
            // খুঁজে পাওয়া যায়নি ("relro" নামের অংশও নেই) — Chromium-এর
            // R8 build field-এর নাম বদলে দেয় (a, b, c...), কিন্তু TYPE
            // বদলায় না। relro field-এর type IRelroLibInfo। নাম+type
            // দুটোই trace করো, যাতে আসল নাম দেখা যায়। relro null করা
            // ব্যর্থ হলেও --no-sandbox append চলবে — দুটো আলাদা try/catch।
            StringBuilder fieldNames = new StringBuilder();
            for (java.lang.reflect.Field f : argsClass.getDeclaredFields()) {
                if (fieldNames.length() > 0) fieldNames.append(',');
                fieldNames.append(f.getName()).append(':')
                        .append(f.getType().getSimpleName());
            }
            BlackBoxCore.traceInitPublic(BlackBoxCore.getContext(),
                    "v1.0.89 IChildProcessArgs fields: " + fieldNames);
            try {
                java.lang.reflect.Field relroField = findFieldByTypeName(argsClass, "relro");
                relroField.setAccessible(true);
                Object oldRelro = relroField.get(args);
                relroField.set(args, null);
                BlackBoxCore.traceInitPublic(BlackBoxCore.getContext(),
                        "v1.0.82 setupConnection relroInfo nulled (field="
                        + relroField.getName() + ", was " + (oldRelro != null) + ")");
            } catch (Throwable t) {
                BlackBoxCore.traceInitPublic(BlackBoxCore.getContext(),
                        "v1.0.89 relro null failed: " + t);
            }
            // v1.0.84: --no-sandbox যোগ করো — M156-এ Android renderer-এর
            // seccomp sandbox বন্ধ করার একমাত্র কার্যকর switch এটাই
            // (--disable-seccomp-filter-sandbox Android-এ ignore হয়)।
            // seccomp filter সব thread-এ বসে (TSYNC) — BlackBox stub-এর
            // Binder/ioctl/prctl syscall-এ SIGSYS হয়ে renderer মরে
            // (21:47:53 cr_seccomp)। commandLine browser-ই বানায়, child
            // RendererMain-এ HasSwitch(kNoSandbox) দেখে sandbox বাদ দেয়।
            try {
                java.lang.reflect.Field cmdField = findFieldByType(argsClass, String[].class);
                cmdField.setAccessible(true);
                String[] cmd = (String[]) cmdField.get(args);
                if (cmd != null) {
                    boolean hasIt = false;
                    for (String s : cmd) {
                        if ("--no-sandbox".equals(s)) { hasIt = true; break; }
                    }
                    if (!hasIt) {
                        String[] newCmd = java.util.Arrays.copyOf(cmd, cmd.length + 1);
                        newCmd[cmd.length] = "--no-sandbox";
                        cmdField.set(args, newCmd);
                        BlackBoxCore.traceInitPublic(BlackBoxCore.getContext(),
                                "v1.0.84 --no-sandbox appended to child commandLine (was "
                                + cmd.length + " args)");
                    }
                } else {
                    BlackBoxCore.traceInitPublic(BlackBoxCore.getContext(),
                            "v1.0.84 child commandLine is null");
                }
            } catch (Throwable t) {
                BlackBoxCore.traceInitPublic(BlackBoxCore.getContext(),
                        "v1.0.84 --no-sandbox append failed: " + t);
            }
            // নতুন parcel বানিয়ে আসল binder-এ পাঠাও।
            Parcel newData = Parcel.obtain();
            try {
                newData.writeInterfaceToken(CHILD_SERVICE_DESCRIPTOR);
                // v1.0.87: পড়ার মতোই writeTypedObject দিয়ে লেখো — marker (1)
                // + parcelable, নইলে আসল Stub পড়তে পারবে না।
                newData.writeTypedObject((Parcelable) args, 0);
                newData.writeStrongBinder(parentBinder);
                newData.writeBinderList(clientInterfaces);
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
            // v1.0.90: Chromium child service — stopService-এ onDestroy()
            // ডেকো না (System.exit(0) করে renderer মেরে দেয়)। v1.0.75-এ
            // onUnbind/onDestroy-এ keep-alive বসেছিল, কিন্তু stopService-এ
            // গার্ড ছিল না — v1.0.89-এ renderer নিঃশব্দে মরেছে (টম্বস্টোন
            // ছাড়া), এটাই পরের ফাঁক। cache-এ রেখে দাও।
            String svcName = record.getService().getClass().getName();
            boolean isChromiumChild = svcName.contains("ProcessService")
                    && svcName.contains("chromium");
            if (isChromiumChild) {
                BlackBoxCore.traceInitPublic(BlackBoxCore.getContext(),
                        "stopService chromium child kept alive: " + svcName);
                return;
            }
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
