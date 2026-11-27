package top.niunaijun.blackbox.core.system;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.env.AppSystemEnv;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.core.system.accounts.BAccountManagerService;
import top.niunaijun.blackbox.core.system.am.BActivityManagerService;
import top.niunaijun.blackbox.core.system.am.BJobManagerService;
import top.niunaijun.blackbox.core.system.location.BLocationManagerService;
import top.niunaijun.blackbox.core.system.notification.BNotificationManagerService;
import top.niunaijun.blackbox.core.system.os.BStorageManagerService;
import top.niunaijun.blackbox.core.system.pm.BPackageInstallerService;
import top.niunaijun.blackbox.core.system.pm.BPackageManagerService;

import top.niunaijun.blackbox.core.system.user.BUserHandle;
import top.niunaijun.blackbox.core.system.user.BUserManagerService;
import top.niunaijun.blackbox.entity.pm.InstallOption;
import top.niunaijun.blackbox.utils.FileUtils;

import top.niunaijun.blackbox.core.system.JarManager;


public class BlackBoxSystem {
    private static BlackBoxSystem sBlackBoxSystem;
    private final List<ISystemService> mServices = new ArrayList<>();
    private final static AtomicBoolean isStartup = new AtomicBoolean(false);

    public static BlackBoxSystem getSystem() {
        if (sBlackBoxSystem == null) {
            synchronized (BlackBoxSystem.class) {
                if (sBlackBoxSystem == null) {
                    sBlackBoxSystem = new BlackBoxSystem();
                }
            }
        }
        return sBlackBoxSystem;
    }

    // v1.0.162: BlackStage ট্রেস helper (SystemCallProvider দেখো)।
    // startup()-এর প্রতিটি ধাপের শুরু/শেষ আলাদা লাইনে লেখা হয় —
    // wake-এ :black মরলে শেষ "done" লাইনটাই বলে দেয় মৃত্যু ঠিক
    // কোন ধাপে। traceInitPublic নিজে সব Throwable গেলে।
    private static void traceStage162(String stage) {
        BlackBoxCore.traceInitPublic(BlackBoxCore.getContext(), "BlackStage: " + stage);
    }

    public void startup() {
        if (isStartup.getAndSet(true))
            return;
        long startupStart = System.currentTimeMillis();
        traceStage162("startup begin");
        BEnvironment.load();
        traceStage162("env load done elapsed=" + (System.currentTimeMillis() - startupStart) + "ms");

        mServices.add(BPackageManagerService.get());
        mServices.add(BUserManagerService.get());
        mServices.add(BActivityManagerService.get());
        mServices.add(BJobManagerService.get());
        mServices.add(BStorageManagerService.get());
        mServices.add(BPackageInstallerService.get());

        mServices.add(BProcessManagerService.get());
        mServices.add(BAccountManagerService.get());
        mServices.add(BLocationManagerService.get());
        mServices.add(BNotificationManagerService.get());
        traceStage162("services collected elapsed=" + (System.currentTimeMillis() - startupStart) + "ms");

        for (ISystemService service : mServices) {
            String serviceName = service.getClass().getSimpleName();
            long serviceStart = System.currentTimeMillis();
            traceStage162("systemReady begin " + serviceName);
            service.systemReady();
            traceStage162("systemReady done " + serviceName
                    + " elapsed=" + (System.currentTimeMillis() - serviceStart) + "ms");
        }

        // v1.0.161: the pre-install pass reads the package list, which
        // is now loaded on BPackageManagerService's PmReady thread —
        // run this pass on a background thread that waits for the
        // scan, instead of blocking startup (SystemCallProvider
        // .onCreate) on it.
        Thread preInstallThread = new Thread(() -> {
            long preInstallStart = System.currentTimeMillis();
            boolean pmReady = BPackageManagerService.get().awaitReady(20000);
            traceStage162("PreInstall begin pmReady=" + pmReady
                    + " waited=" + (System.currentTimeMillis() - preInstallStart) + "ms");
            List<String> preInstallPackages = AppSystemEnv.getPreInstallPackages();
            for (String preInstallPackage : preInstallPackages) {
                try {
                    if (!BPackageManagerService.get().isInstalled(preInstallPackage, BUserHandle.USER_ALL)) {
                        PackageInfo packageInfo = BlackBoxCore.getPackageManager().getPackageInfo(preInstallPackage, 0);
                        BPackageManagerService.get().installPackageAsUser(packageInfo.applicationInfo.sourceDir, InstallOption.installBySystem(), BUserHandle.USER_ALL);
                    }
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
            traceStage162("PreInstall done elapsed=" + (System.currentTimeMillis() - preInstallStart) + "ms");
        }, "PreInstall");
        preInstallThread.setDaemon(true);
        preInstallThread.start();
        traceStage162("preinstall thread started elapsed=" + (System.currentTimeMillis() - startupStart) + "ms");

        JarManager.getInstance().initializeAsync();
        traceStage162("startup done total=" + (System.currentTimeMillis() - startupStart) + "ms");
        
        
     
    }
}
