package top.niunaijun.blackbox.core;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.entity.pm.InstallResult;
import top.niunaijun.blackbox.utils.NativeUtils;


public class GmsCore {
    private static final String TAG = "GmsCore";

    private static final HashSet<String> GOOGLE_APP = new HashSet<>();
    private static final HashSet<String> GOOGLE_SERVICE = new HashSet<>();
    public static final String GMS_PKG = "com.google.android.gms";
    public static final String GSF_PKG = "com.google.android.gsf";
    public static final String VENDING_PKG = "com.android.vending";

    static {
        GOOGLE_APP.add(VENDING_PKG);
        GOOGLE_APP.add("com.google.android.play.games");
        GOOGLE_APP.add("com.google.android.wearable.app");
        GOOGLE_APP.add("com.google.android.wearable.app.cn");

        
        GOOGLE_SERVICE.add(GMS_PKG);
        GOOGLE_SERVICE.add(GSF_PKG);
        GOOGLE_SERVICE.add("com.google.android.gsf.login");
        GOOGLE_SERVICE.add("com.google.android.backuptransport");
        GOOGLE_SERVICE.add("com.google.android.backup");
        GOOGLE_SERVICE.add("com.google.android.configupdater");
        GOOGLE_SERVICE.add("com.google.android.syncadapters.contacts");
        GOOGLE_SERVICE.add("com.google.android.feedback");
        GOOGLE_SERVICE.add("com.google.android.onetimeinitializer");
        GOOGLE_SERVICE.add("com.google.android.partnersetup");
        GOOGLE_SERVICE.add("com.google.android.setupwizard");
        GOOGLE_SERVICE.add("com.google.android.syncadapters.calendar");
    }

    public static boolean isGoogleService(String packageName) {
        return GOOGLE_SERVICE.contains(packageName);
    }

    public static boolean isGoogleAppOrService(String str) {
        return GOOGLE_APP.contains(str) || GOOGLE_SERVICE.contains(str);
    }

    private static InstallResult installPackages(Set<String> list, int userId) {
        BlackBoxCore blackBoxCore = BlackBoxCore.get();
        for (String packageName : list) {
            boolean alreadyInstalled = blackBoxCore.isInstalled(packageName, userId);
            if (!alreadyInstalled) {
                try {
                    BlackBoxCore.getContext().getPackageManager().getApplicationInfo(packageName, 0);
                } catch (PackageManager.NameNotFoundException e) {

                    continue;
                }
                InstallResult installResult = blackBoxCore.installPackageAsUser(packageName, userId);
                if (!installResult.success) {
                    return installResult;
                }
            }
            // v1.0.137: native lib কপি — আগে ইনস্টল করা GMS-এর জন্যও (আগে
            // কখনো কপি হয়নি)। ক্লোনের installClone এটা করে (v1.0.21), কিন্তু
            // installGms করতো না; virtual lib dir খালি থাকায় GMS সার্ভিস
            // native কোড লোড করতে না পেরে চালু হতো না।
            copyGmsNativeLibs(packageName);
        }
        return new InstallResult();
    }

    // v1.0.137: হোস্টের আসল base + split APK থেকে .so কপি করো (best-effort)।
    // lib dir-এ ইতিমধ্যে .so থাকলে আবার কপি কোরো না (প্রতি লঞ্চে IO বাঁচাতে)।
    private static void copyGmsNativeLibs(String packageName) {
        try {
            File libDir = BEnvironment.getAppLibDir(packageName);
            File[] existing = libDir.listFiles();
            if (existing != null) {
                for (File f : existing) {
                    if (f.getName().endsWith(".so")) {
                        return;
                    }
                }
            }
            ApplicationInfo hostAi = BlackBoxCore.getPackageManager().getApplicationInfo(packageName, 0);
            List<String> apks = new ArrayList<>();
            if (hostAi.sourceDir != null) {
                apks.add(hostAi.sourceDir);
            }
            if (hostAi.splitSourceDirs != null) {
                for (String s : hostAi.splitSourceDirs) {
                    apks.add(s);
                }
            }
            for (String apkPath : apks) {
                try {
                    NativeUtils.copyNativeLib(new File(apkPath), libDir);
                } catch (Throwable ignored) {
                }
            }
            BlackBoxCore.traceInitPublic(BlackBoxCore.getContext(),
                    "v1.0.137 GMS native libs copied: " + packageName);
        } catch (Throwable ignored) {
        }
    }

    private static void uninstallPackages(Set<String> list, int userId) {
        BlackBoxCore blackBoxCore = BlackBoxCore.get();
        for (String packageName : list) {
            blackBoxCore.uninstallPackageAsUser(packageName, userId);
        }
    }

    public static InstallResult installGApps(int userId) {
        Set<String> googleApps = new HashSet<>();

        googleApps.addAll(GOOGLE_SERVICE);
        googleApps.addAll(GOOGLE_APP);

        InstallResult installResult = installPackages(googleApps, userId);
        if (!installResult.success) {
            uninstallGApps(userId);
            return installResult;
        }
        return installResult;
    }

    public static void uninstallGApps(int userId) {
        uninstallPackages(GOOGLE_SERVICE, userId);
        uninstallPackages(GOOGLE_APP, userId);
    }

    public static void remove(String packageName) {
        GOOGLE_SERVICE.remove(packageName);
        GOOGLE_APP.remove(packageName);
    }


    public static boolean isSupportGms() {
        try {
            BlackBoxCore.getPackageManager().getPackageInfo(GMS_PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return false;
    }

    public static boolean isInstalledGoogleService(int userId) {
        return BlackBoxCore.get().isInstalled(GMS_PKG, userId);
    }
}