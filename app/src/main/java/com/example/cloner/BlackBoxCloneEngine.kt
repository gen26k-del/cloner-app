package com.example.cloner

import top.niunaijun.blackbox.BlackBoxCore
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * BlackBox ইঞ্জিনকে CloneEngine ইন্টারফেসের সাথে যুক্ত করার অ্যাডাপ্টার।
 * API যাচাই করা হয়েছে ALEX5402/NewBlackbox ফর্কের BlackBoxCore.java থেকে।
 *
 * গুরুত্বপূর্ণ ফিক্স: আধুনিক অ্যাপগুলো split APK-তে থাকে
 * (base.apk + split_config.arm64_v8a.apk + ... )। ইঞ্জিনের
 * installPackageAsUser(String) শুধু base APK নেয় — split বাদ পড়লে
 * native library ছাড়া অ্যাপ চালু হয় না। তাই split থাকলে
 * lib/ এন্ট্রিগুলো merge করে একটা APK বানিয়ে ইনস্টল করা হয়।
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
     * split APK থাকলে native lib merge করে ইনস্টল করা হয়।
     */
    override fun installClone(packageName: String): Result<Unit> = runCatching {
        val ctx = BlackBoxCore.getContext()
        val appInfo = ctx.packageManager.getApplicationInfo(packageName, 0)
        val splits = appInfo.splitSourceDirs?.toList().orEmpty()

        if (splits.isEmpty()) {
            // single APK — সরাসরি ইনস্টল
            val result = core().installPackageAsUser(packageName, userId)
            if (!result.success) {
                throw IllegalStateException(result.msg ?: "ইনস্টল ব্যর্থ হয়েছে")
            }
            return@runCatching Unit
        }

        // split APK: base + সব split-এর lib/ merge করে একটা APK বানাও
        var merged: File? = null
        try {
            merged = mergeSplits(ctx.cacheDir, appInfo.sourceDir, splits)
            val result = core().installPackageAsUser(merged, userId)
            if (!result.success) {
                throw IllegalStateException(result.msg ?: "ইনস্টল ব্যর্থ হয়েছে")
            }
        } finally {
            try { merged?.delete() } catch (_: Exception) { }
        }
        Unit
    }

    /**
     * base APK-এর সব এন্ট্রি + প্রতিটা split-এর lib/<abi>/*.so
     * একটা নতুন APK-তে জোড়া দেয়। resources.arsc merge করা হয় না
     * (split-এর নিজস্ব arsc base-এর সাথে conflict করে) — lib ছাড়া
     * বাকি resource-এর অভাবে অ্যাপ সাধারণত crash করে না।
     */
    private fun mergeSplits(cacheDir: File, baseApk: String, splits: List<String>): File {
        val out = File(cacheDir, "merged_${System.currentTimeMillis()}.apk")
        val addedLibs = HashSet<String>()

        fun copyEntries(zos: ZipOutputStream, apkPath: String, onlyLib: Boolean) {
            ZipFile(apkPath).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.isDirectory) continue
                    val name = e.name
                    if (onlyLib) {
                        if (!name.startsWith("lib/")) continue
                        if (!addedLibs.add(name)) continue
                    }
                    val newEntry = ZipEntry(name)
                    newEntry.time = e.time
                    newEntry.method = e.method
                    if (e.method == ZipEntry.STORED) {
                        newEntry.size = e.size
                        newEntry.crc = e.crc
                    }
                    zos.putNextEntry(newEntry)
                    zf.getInputStream(e).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }

        ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zos ->
            copyEntries(zos, baseApk, onlyLib = false)
            for (s in splits) {
                try { copyEntries(zos, s, onlyLib = true) } catch (_: Exception) { }
            }
        }
        return out
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
        if (!isCloneInstalled(packageName)) {
            throw IllegalStateException("ক্লোন ইনস্টল করা নেই — আগে ক্লোন করুন")
        }
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
