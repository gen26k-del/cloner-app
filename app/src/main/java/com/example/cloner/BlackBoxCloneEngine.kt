package com.example.cloner

import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.env.BEnvironment
import top.niunaijun.blackbox.utils.NativeUtils
import java.io.File

/**
 * BlackBox ইঞ্জিনকে CloneEngine ইন্টারফেসের সাথে যুক্ত করার অ্যাডাপ্টার।
 * API যাচাই করা হয়েছে ALEX5402/NewBlackbox ফর্কের BlackBoxCore.java থেকে।
 *
 * ইনস্টল পদ্ধতি: ইঞ্জিনের installPackageAsUser(String) — এটাই
 * পরীক্ষিতভাবে কাজ করে। split APK-এর native lib (.so) গুলো
 * ইঞ্জিনের নিজের NativeUtils দিয়ে lib ডিরেক্টরিতে কপি করা হয়
 * (ইঞ্জিন STORAGE-ইনস্টলে নিজেই এটা করে; SYSTEM-ইনস্টলে বাদ পড়ে)।
 */
class BlackBoxCloneEngine : CloneEngine {

    override val name: String = "BlackBox"

    /** এক অ্যাপের সর্বোচ্চ ক্লোন সংখ্যা (userId 0..MAX) */
    private val maxUserId: Int = 9

    private fun core(): BlackBoxCore = BlackBoxCore.get()

    override fun isReady(): Boolean = try {
        core() != null
    } catch (_: Exception) {
        false
    }

    /**
     * সিস্টেমে ইনস্টল থাকা অ্যাপ ক্লোন করে।
     * userId আলাদা হলে একই অ্যাপের আরেকটা কপি (আলাদা ডেটা) হয়।
     * ইনস্টলের পর split/base APK থেকে native lib কপি করা হয় (best-effort)।
     */
    override fun installClone(packageName: String, userId: Int): Result<Unit> = runCatching {
        val result = core().installPackageAsUser(packageName, userId)
        if (!result.success) {
            throw IllegalStateException(result.msg ?: "ইনস্টল ব্যর্থ হয়েছে")
        }
        copyNativeLibs(packageName)
        Unit
    }

    /**
     * base + সব split APK থেকে native lib (.so ফাইল) ইঞ্জিনের
     * অ্যাপ-lib ডিরেক্টরিতে কপি করে। ব্যর্থ হলে চুপচাপ এড়িয়ে যায় —
     * ইনস্টল ইতিমধ্যে সফল হয়েছে।
     */
    private fun copyNativeLibs(packageName: String) {
        try {
            val ctx = BlackBoxCore.getContext()
            val appInfo = ctx.packageManager.getApplicationInfo(packageName, 0)
            val libDir = BEnvironment.getAppLibDir(packageName)
            val apks = listOf(appInfo.sourceDir) + appInfo.splitSourceDirs.orEmpty()
            for (apkPath in apks) {
                try {
                    NativeUtils.copyNativeLib(File(apkPath), libDir)
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
    }

    /** ডাউনলোড করা APK ফাইল থেকে ভার্চুয়াল অ্যাপ ইনস্টল করে */
    fun installApk(apkFile: File): Result<Unit> = runCatching {
        require(apkFile.exists()) { "APK ফাইল পাওয়া যায়নি: ${apkFile.path}" }
        val result = core().installPackageAsUser(apkFile, 0)
        if (!result.success) {
            throw IllegalStateException(result.msg ?: "ইনস্টল ব্যর্থ হয়েছে")
        }
        Unit
    }

    override fun launchClone(packageName: String, userId: Int): Result<Unit> = runCatching {
        if (!isCloneInstalled(packageName, userId)) {
            throw IllegalStateException("ক্লোন ইনস্টল করা নেই — আগে ক্লোন করুন")
        }
        val pm = BlackBoxCore.getBPackageManager()
        val intent = try {
            pm.getLaunchIntentForPackage(packageName, userId)
        } catch (_: Exception) {
            null
        }
        if (intent != null) {
            val ok = core().launchApk(packageName, userId)
            if (!ok) throw IllegalStateException(
                "intent আছে (${intent.component?.className}) কিন্তু launchApk false"
            )
            return@runCatching Unit
        }
        // ফলব্যাক: লঞ্চ intent না মিললে (Chrome-এর ক্ষেত্রে) প্যাকেজের
        // অ্যাক্টিভিটি তালিকা থেকে সরাসরি মূল অ্যাক্টিভিটি চালু করো
        val pkgInfo = try {
            pm.getPackageInfo(
                packageName,
                android.content.pm.PackageManager.GET_ACTIVITIES,
                userId
            )
        } catch (_: Exception) {
            null
        }
        val activities = pkgInfo?.activities
            ?.filter { it.enabled && it.exported } ?: emptyList()
        val target = activities.firstOrNull { it.name.endsWith(".Main") }
            ?: activities.firstOrNull()
            ?: throw IllegalStateException("কোনো অ্যাক্টিভিটিই পাওয়া যায়নি (intent null)")
        val clsName = if (target.name.startsWith(".")) packageName + target.name else target.name
        val explicit = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            setClassName(packageName, clsName)
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        }
        core().startActivity(explicit, userId)
        Unit
    }

    /** sandbox থেকে ক্লোন মুছে ফেলে */
    override fun uninstallClone(packageName: String, userId: Int): Result<Unit> = runCatching {
        core().uninstallPackageAsUser(packageName, userId)
        Unit
    }

    override fun listClones(): List<CloneId> = try {
        val out = mutableListOf<CloneId>()
        for (uid in 0..maxUserId) {
            try {
                core().getInstalledApplications(0, uid)
                    .filter { !isGmsSystemPackage(it.packageName) }
                    .mapTo(out) { CloneId(it.packageName, uid) }
            } catch (_: Exception) { }
        }
        out
    } catch (_: Exception) {
        emptyList()
    }

    override fun nextUserId(packageName: String): Int {
        for (uid in 0..maxUserId) {
            if (!isCloneInstalled(packageName, uid)) return uid
        }
        return -1
    }

    fun isCloneInstalled(packageName: String, userId: Int = 0): Boolean = try {
        core().isInstalled(packageName, userId)
    } catch (_: Exception) {
        false
    }
}
