package com.example.cloner

import top.niunaijun.blackbox.BlackBoxCore
import java.io.File

/**
 * BlackBox ইঞ্জিনকে CloneEngine ইন্টারফেসের সাথে যুক্ত করার অ্যাডাপ্টার।
 * API যাচাই করা হয়েছে ALEX5402/NewBlackbox ফর্কের BlackBoxCore.java থেকে।
 */
class BlackBoxCloneEngine : CloneEngine {

    override val name: String = "BlackBox"

    /** BlackBox-এর ডিফল্ট ভার্চুয়াল ইউজার */
    private val userId: Int = 0

    private fun core(): BlackBoxCore = BlackBoxCore.get()

    override fun isReady(): Boolean = try {
        core() != null
    } catch (_: Exception) {
        false
    }

    /**
     * সিস্টেমে ইনস্টল থাকা অ্যাপ ক্লোন করে।
     * BlackBox ভেতরে ভেতরে APK পাথ বের করে নেয়।
     */
    override fun installClone(packageName: String): Result<Unit> = runCatching {
        val result = core().installPackageAsUser(packageName, userId)
        if (!result.success) {
            throw IllegalStateException(result.msg ?: "ইনস্টল ব্যর্থ হয়েছে")
        }
        Unit
    }

    /** ডাউনলোড করা APK ফাইল থেকে ভার্চুয়াল অ্যাপ ইনস্টল করে */
    fun installApk(apkFile: File): Result<Unit> = runCatching {
        require(apkFile.exists()) { "APK ফাইল পাওয়া যায়নি: ${apkFile.path}" }
        val result = core().installPackageAsUser(apkFile, userId)
        if (!result.success) {
            throw IllegalStateException(result.msg ?: "ইনস্টল ব্যর্থ হয়েছে")
        }
        Unit
    }

    override fun launchClone(packageName: String): Result<Unit> = runCatching {
        val ok = core().launchApk(packageName, userId)
        if (!ok) throw IllegalStateException("অ্যাপ চালু করা যায়নি")
        Unit
    }

    /** sandbox থেকে ক্লোন মুছে ফেলে */
    override fun uninstallClone(packageName: String): Result<Unit> = runCatching {
        core().uninstallPackageAsUser(packageName, userId)
        Unit
    }

    override fun listClones(): List<String> = try {
        core().getInstalledApplications(0, userId).map { it.packageName }
    } catch (_: Exception) {
        emptyList()
    }

    fun isCloneInstalled(packageName: String): Boolean = try {
        core().isInstalled(packageName, userId)
    } catch (_: Exception) {
        false
    }
}
