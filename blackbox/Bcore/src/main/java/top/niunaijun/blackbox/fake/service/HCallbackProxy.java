package top.niunaijun.blackbox.fake.service;

import android.app.Application;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import black.android.app.ActivityThreadActivityClientRecordContext;
import black.android.app.BRActivityClient;
import black.android.app.BRActivityClientActivityClientControllerSingleton;
import black.android.app.BRActivityManagerNative;
import black.android.app.BRActivityThread;
import black.android.app.BRActivityThreadActivityClientRecord;
import black.android.app.BRActivityThreadCreateServiceData;
import black.android.app.BRActivityThreadH;
import black.android.app.BRContextImpl;
import black.android.app.BRIActivityManager;
import black.android.app.BRService;
import black.android.app.servertransaction.BRClientTransaction;
import black.android.app.servertransaction.BRLaunchActivityItem;
import black.android.app.servertransaction.LaunchActivityItemContext;
import black.android.os.BRHandler;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.IInjectHook;
import top.niunaijun.blackbox.proxy.ProxyManifest;
import top.niunaijun.blackbox.proxy.record.ProxyActivityRecord;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.BuildCompat;
import top.niunaijun.blackbox.utils.compat.ContextCompat;



public class HCallbackProxy implements IInjectHook, Handler.Callback {
    public static final String TAG = "HCallbackStub";
    private Handler.Callback mOtherCallback;
    private AtomicBoolean mBeing = new AtomicBoolean(false);

    private Handler.Callback getHCallback() {
        return BRHandler.get(getH()).mCallback();
    }

    private Handler getH() {
        Object currentActivityThread = BlackBoxCore.mainThread();
        return BRActivityThread.get(currentActivityThread).mH();
    }

    @Override
    public void injectHook() {
        mOtherCallback = getHCallback();
        if (mOtherCallback != null && (mOtherCallback == this || mOtherCallback.getClass().getName().equals(this.getClass().getName()))) {
            mOtherCallback = null;
        }
        BRHandler.get(getH())._set_mCallback(this);
    }

    @Override
    public boolean isBadEnv() {
        Handler.Callback hCallback = getHCallback();
        return hCallback != null && hCallback != this;
    }

    @Override
    public boolean handleMessage(@NonNull Message msg) {
        if (!mBeing.getAndSet(true)) {
            try {
                if (BuildCompat.isPie()) {
                    if (msg.what == BRActivityThreadH.get().EXECUTE_TRANSACTION()) {
                        if (handleLaunchActivity(msg.obj)) {
                            getH().sendMessageAtFrontOfQueue(Message.obtain(msg));
                            return true;
                        }
                    }
                } else {
                    if (msg.what == BRActivityThreadH.get().LAUNCH_ACTIVITY()) {
                        if (handleLaunchActivity(msg.obj)) {
                            getH().sendMessageAtFrontOfQueue(Message.obtain(msg));
                            return true;
                        }
                    }
                }
                if (msg.what == BRActivityThreadH.get().CREATE_SERVICE()) {
                    return handleCreateService(msg.obj);
                }
                if (mOtherCallback != null) {
                    return mOtherCallback.handleMessage(msg);
                }
                return false;
            } finally {
                mBeing.set(false);
            }
        }
        return false;
    }

    private Object getLaunchActivityItem(Object clientTransaction) {
        List<Object> mActivityCallbacks = BRClientTransaction.get(clientTransaction).mActivityCallbacks();

        if (mActivityCallbacks == null) {
            Slog.e(TAG, "mActivityCallbacks is null for clientTransaction: " + clientTransaction);
            return null;
        }

        for (Object obj : mActivityCallbacks) {
            if (BRLaunchActivityItem.getRealClass().getName().equals(obj.getClass().getCanonicalName())) {
                return obj;
            }
        }
        return null;
    }

    private boolean handleLaunchActivity(Object client) {
        Object r;
        if (BuildCompat.isPie()) {
            
            r = getLaunchActivityItem(client);
        } else {
            
            r = client;
        }
        if (r == null)
            return false;

        Intent intent;
        IBinder token;
        if (BuildCompat.isPie()) {
            intent = BRLaunchActivityItem.get(r).mIntent();
            token = BRClientTransaction.get(client).mActivityToken();
        } else {
            ActivityThreadActivityClientRecordContext clientRecordContext = BRActivityThreadActivityClientRecord.get(r);
            intent = clientRecordContext.intent();
            token = clientRecordContext.token();
        }

        if (intent == null)
            return false;

        ProxyActivityRecord stubRecord = ProxyActivityRecord.create(intent);
        ActivityInfo activityInfo = stubRecord.mActivityInfo;
        if (activityInfo != null) {
            // v1.0.155: launch-timing শুরু — এই transaction-এর activity-র
            // onResume পর্যন্ত সময় AppInstrumentation মাপবে (LaunchTime /
            // SlowLaunch ট্রেস)। recovery পথে re-dispatch হলে start আবার
            // লেখা হয় — bind+create-এর মূল খরচটাই তখন থেকে মাপা হয়।
            BActivityThread.noteLaunchStart155(activityInfo.name);
            // v1.0.149: launch-assignment trace + package guard. আগে এই
            // প্রসেস অন্য অ্যাপের জন্য bound+init হয়ে থাকলে (যেমন :black
            // restart-এর পর server record মুছে গেলেও guest বেঁচে থাকে)
            // কোনো পরীক্ষা ছাড়াই আগত activity সেই বিদেশি Application-এর
            // উপর instantiate হতো — 2026-11-22 16:12:34-এ Chrome-bound
            // প্রসেসে FbMainTabActivity গিয়ে "fbApp has not been
            // initialized" ক্র্যাশ করেছে। এখন mismatch হলে unbound
            // recovery পথেই (restartProcess + launch rewrite) পাঠাই।
            String boundPkg149 = BActivityThread.getAppPackageName();
            boolean boundInit149 = BActivityThread.currentActivityThread().isInit();
            boolean launchMismatch149 = BActivityThread.getAppConfig() != null
                    && boundInit149
                    && boundPkg149 != null
                    && !boundPkg149.equals(activityInfo.packageName);
            try {
                top.niunaijun.blackbox.BlackBoxCore.traceInitPublic(
                    top.niunaijun.blackbox.BlackBoxCore.getContext(),
                    "launch assigned: activity=" + activityInfo.packageName + "/" + activityInfo.name
                        + " stub=" + BActivityThread.getAppProcessName()
                        + " boundPkg=" + boundPkg149 + " boundInit=" + boundInit149
                        + (launchMismatch149 ? " LAUNCH MISMATCH" : ""));
            } catch (Throwable ignored) { }
            if (BActivityThread.getAppConfig() == null || launchMismatch149) {
                BlackBoxCore.getBActivityManager().restartProcess(activityInfo.packageName, activityInfo.processName, stubRecord.mUserId);

                Intent launchIntentForPackage = BlackBoxCore.getBPackageManager().getLaunchIntentForPackage(activityInfo.packageName, stubRecord.mUserId);
                intent.setExtrasClassLoader(this.getClass().getClassLoader());
                ProxyActivityRecord.saveStub(intent, launchIntentForPackage, stubRecord.mActivityInfo, stubRecord.mActivityRecord, stubRecord.mUserId);
                if (BuildCompat.isPie()) {
                    LaunchActivityItemContext launchActivityItemContext = BRLaunchActivityItem.get(r);
                    launchActivityItemContext._set_mIntent(intent);
                    launchActivityItemContext._set_mInfo(activityInfo);
                } else {
                    ActivityThreadActivityClientRecordContext clientRecordContext = BRActivityThreadActivityClientRecord.get(r);
                    clientRecordContext._set_intent(intent);
                    clientRecordContext._set_activityInfo(activityInfo);
                }
                return true;
            }
            
            if (!BActivityThread.currentActivityThread().isInit()) {
                BActivityThread.currentActivityThread().bindApplication(activityInfo.packageName,
                        activityInfo.processName);
                // bind শেষ, এখন fall through করে activity launch করো
                // (আগে return true করে launch মেসেজ গিলে ফেলা হতো)
                try {
                    top.niunaijun.blackbox.BlackBoxCore.traceInitPublic(
                        top.niunaijun.blackbox.BlackBoxCore.getContext(),
                        "HCallbackProxy bind done, launching: " + activityInfo.packageName);
                } catch (Throwable ignored) { }
            }

            int taskId = BRIActivityManager.get(BRActivityManagerNative.get().getDefault()).getTaskForActivity(token, false);
            BlackBoxCore.getBActivityManager().onActivityCreated(taskId, token, stubRecord.mActivityRecord);
            // activity-alias হলে intent-এর কম্পোনেন্ট alias নামেই থাকে
            // (যেমন Chrome/Brave-এর com.google.android.apps.chrome.Main) —
            // ActivityThread performLaunchActivity intent-এর কম্পোনেন্ট দিয়ে
            // ক্লাস লোড করে, alias ক্লাস নয় বলে ClassNotFoundException হয় আর
            // অ্যাক্টিভিটি শুরু হয় না (bind হয়, onCreate আসে না)। resolved
            // ActivityInfo-র আসল অ্যাক্টিভিটিতে কম্পোনেন্ট বদলে দাও — আসল
            // সিস্টেমও alias resolve করে টার্গেটই চালায়।
            if (stubRecord.mTarget != null && stubRecord.mTarget.getComponent() != null) {
                ComponentName resolved = new ComponentName(activityInfo.packageName, activityInfo.name);
                if (!stubRecord.mTarget.getComponent().equals(resolved)) {
                    stubRecord.mTarget.setComponent(resolved);
                }
            }
            try {
                top.niunaijun.blackbox.BlackBoxCore.traceInitPublic(
                    top.niunaijun.blackbox.BlackBoxCore.getContext(),
                    "HCallbackProxy launching real activity: " + stubRecord.mTarget.getComponent());
            } catch (Throwable ignored) { }

            if(BuildCompat.isTiramisu()){
                LaunchActivityItemContext launchActivityItemContext = BRLaunchActivityItem.get(r);
                launchActivityItemContext._set_mIntent(stubRecord.mTarget);
                launchActivityItemContext._set_mInfo(activityInfo);
            } else if (BuildCompat.isS()) {
                Object record = BRActivityThread.get(BlackBoxCore.mainThread()).getLaunchingActivity(token);
                ActivityThreadActivityClientRecordContext clientRecordContext = BRActivityThreadActivityClientRecord.get(record);
                clientRecordContext._set_intent(stubRecord.mTarget);
                clientRecordContext._set_activityInfo(activityInfo);
                clientRecordContext._set_packageInfo(BActivityThread.currentActivityThread().getPackageInfo());

                checkActivityClient();
            } else if (BuildCompat.isPie()) {
                LaunchActivityItemContext launchActivityItemContext = BRLaunchActivityItem.get(r);
                launchActivityItemContext._set_mIntent(stubRecord.mTarget);
                launchActivityItemContext._set_mInfo(activityInfo);
            } else {
                ActivityThreadActivityClientRecordContext clientRecordContext = BRActivityThreadActivityClientRecord.get(r);
                clientRecordContext._set_intent(stubRecord.mTarget);
                clientRecordContext._set_activityInfo(activityInfo);
            }
        }
        return false;
    }

    private boolean handleCreateService(Object data) {
        if (BActivityThread.getAppConfig() != null) {
            String appPackageName = BActivityThread.getAppPackageName();
            assert appPackageName != null;

            ServiceInfo serviceInfo = BRActivityThreadCreateServiceData.get(data).info();
            if (!serviceInfo.name.equals(ProxyManifest.getProxyService(BActivityThread.getAppPid()))
                    && !serviceInfo.name.equals(ProxyManifest.getProxyJobService(BActivityThread.getAppPid()))) {
                Slog.d(TAG, "handleCreateService: " + data);
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(appPackageName, serviceInfo.name));
                BlackBoxCore.getBActivityManager().startService(intent, null, false, BActivityThread.getUserId());
                return true;
            }
            // v1.0.168: proxy services take the DualSpaceLivre path below —
            // letting ActivityThread create them the stock way can rebuild
            // the guest LoadedApk and instantiate the guest Application a
            // second time (Meta apps cannot survive that), and leaves the
            // proxy service "executing" until a false service ANR fires.
            return createProxyService(data, serviceInfo);
        }
        return false;
    }

    /**
     * v1.0.168 — ported from DualSpaceLivre (pedrohlsa), HCallbackProxy.
     * CREATE_SERVICE normally asks ActivityThread to resolve the proxy
     * service's LoadedApk. In a virtual process ActivityThread's bound
     * package has already been replaced by the guest, so that lookup may
     * build a second LoadedApk and instantiate the guest Application again.
     * Meta applications cannot survive that: their attachBaseContext/
     * onCreate singletons and network configuration are strictly
     * one-per-process.
     *
     * Create only the host proxy Service here and register it in
     * ActivityThread's normal service maps. Later SERVICE_ARGS/BIND_SERVICE
     * messages then follow the framework lifecycle, while the already
     * initialized guest Application is reused unchanged. Creation is
     * acknowledged to ActivityManager (serviceDoneExecuting) so the proxy
     * service is not left in the "executing" state (false service ANR).
     * Any failure returns false — ActivityThread then handles the message
     * the stock way, exactly as before this change.
     */
    private boolean createProxyService(Object data, ServiceInfo serviceInfo) {
        try {
            IBinder token = BRActivityThreadCreateServiceData.get(data).token();
            Map<IBinder, Service> services =
                    BRActivityThread.get(BlackBoxCore.mainThread()).mServices();
            Map<IBinder, Object> servicesData =
                    BRActivityThread.get(BlackBoxCore.mainThread()).mServicesData();
            if (services != null && services.containsKey(token)) {
                return true;
            }

            ClassLoader hostClassLoader = HCallbackProxy.class.getClassLoader();
            Service service = (Service) hostClassLoader.loadClass(serviceInfo.name).newInstance();
            Context context = BlackBoxCore.getContext().createPackageContext(
                    BlackBoxCore.getHostPkg(),
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            BRContextImpl.get(context).setOuterContext(service);

            Application application = BActivityThread.getApplication();
            if (application == null) {
                application = BRActivityThread.get(BlackBoxCore.mainThread()).mInitialApplication();
            }
            BRService.get(service).attach(
                    context,
                    BlackBoxCore.mainThread(),
                    serviceInfo.name,
                    token,
                    application,
                    BRActivityManagerNative.get().getDefault());
            ContextCompat.fix(context);
            service.onCreate();

            if (services == null || servicesData == null) {
                Slog.e(TAG, "ActivityThread service maps are unavailable for " + serviceInfo.name);
                BlackBoxCore.traceInitPublic(BlackBoxCore.getContext(),
                        "ProxySvc: maps unavailable for " + serviceInfo.name);
                return false;
            }
            services.put(token, service);
            servicesData.put(token, data);
            // ActivityThread normally performs this acknowledgement after
            // Service.onCreate. Because creation is handled above, acknowledge
            // it here so ActivityManager does not leave the proxy service in
            // the "executing" state and raise a false service ANR.
            BRIActivityManager.get(BRActivityManagerNative.get().getDefault())
                    .serviceDoneExecuting(token, 0, 0, 0);
            Slog.d(TAG, "Created proxy service without reinitializing guest: " + serviceInfo.name);
            BlackBoxCore.traceInitPublic(BlackBoxCore.getContext(),
                    "ProxySvc: created " + serviceInfo.name);
            return true;
        } catch (Throwable e) {
            Slog.e(TAG, "Unable to create proxy service safely: " + serviceInfo.name, e);
            BlackBoxCore.traceInitPublic(BlackBoxCore.getContext(),
                    "ProxySvc: FAILED " + serviceInfo.name + " : " + e);
            return false;
        }
    }

    private void checkActivityClient() {
        try {
            Object activityClientController = BRActivityClient.get().getActivityClientController();
            if (!(activityClientController instanceof Proxy)) {
                IActivityClientProxy iActivityClientProxy = new IActivityClientProxy(activityClientController);
                iActivityClientProxy.onlyProxy(true);
                iActivityClientProxy.injectHook();
                Object instance = BRActivityClient.get().getInstance();
                Object o = BRActivityClient.get(instance).INTERFACE_SINGLETON();
                BRActivityClientActivityClientControllerSingleton.get(o)._set_mKnownInstance(iActivityClientProxy.getProxyInvocation());
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
