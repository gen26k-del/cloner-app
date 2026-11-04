package com.example.cloner

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.configuration.ClientConfiguration
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/**
 * অ্যাপের Application ক্লাস — BlackBox ভার্চুয়াল ইঞ্জিনের দুই ধাপের init এখানে হয়।
 * ক্রম বাধ্যতামূলক: আগে doAttachBaseContext(), তারপর onCreate()-এ doCreate()।
 */
class ClonerApp : Application() {

    /**
     * ANR watchdog: main thread 8 সেকেন্ডের বেশি সাড়া না দিলে
     * সব thread-এর stack trace ফাইলে লিখে রাখে।
     */
    private fun startAnrWatchdog() {
        try {
            val procName = runCatching {
                java.io.File("/proc/self/cmdline").readText()
                    .replace("\u0000", " ").trim().ifEmpty { "?" }
            }.getOrDefault("?")
            val safeProc = procName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val reportFile = File(cacheDir, "last_crash_$safeProc.txt")
            val mainHandler = Handler(Looper.getMainLooper())
            val state = longArrayOf(SystemClock.uptimeMillis())
            // main thread আটকানোর আগের অবস্থান — watchdog thread থেকে স্যাম্পল
            val recentStacks = ArrayDeque<String>()
            // আসল main thread: যার stack-এ ActivityThread.main আছে
            fun findRealMain(): Thread? {
                return try {
                    val all = Thread.getAllStackTraces()
                    all.keys.find { t ->
                        all[t]?.any {
                            it.className == "android.app.ActivityThread" &&
                                it.methodName == "main"
                        } == true
                    }
                } catch (_: Exception) { null }
            }
            val ticker = object : Runnable {
                override fun run() {
                    state[0] = SystemClock.uptimeMillis()
                    mainHandler.postDelayed(this, 2000)
                }
            }
            mainHandler.post(ticker)
            Thread({
                while (true) {
                    try {
                        // প্রতি সেকেন্ডে আসল main thread-এর stack স্যাম্পল করো
                        try {
                            val rm = findRealMain()
                            val st = rm?.stackTrace
                            if (rm != null && st != null && st.isNotEmpty()) {
                                val top = st.take(10).joinToString("\n") { "  at $it" }
                                synchronized(recentStacks) {
                                    recentStacks.addLast("[${Date()}] $top")
                                    if (recentStacks.size > 8) recentStacks.removeFirst()
                                }
                            }
                        } catch (_: Exception) { }
                        Thread.sleep(3000)
                        val blockedFor = SystemClock.uptimeMillis() - state[0]
                        if (blockedFor > 8000) {
                            val sb = StringBuilder("ANR ধরা পড়েছে: ${Date()} [$procName]\n")
                            // init trace ফাইল থাকলে যোগ করো — ঠিক কোথায় আটকেছে দেখাবে
                            try {
                                val traceFile = File(cacheDir, "init_trace.txt")
                                if (traceFile.exists()) {
                                    sb.append("\n--- init trace ---\n")
                                    sb.append(traceFile.readText().take(3000))
                                    sb.append("\n--- end trace ---\n")
                                }
                            } catch (_: Exception) { }
                            sb.append("\n--- main thread-এর সাম্প্রতিক অবস্থান (watchdog স্যাম্পল) ---\n")
                            synchronized(recentStacks) {
                                if (recentStacks.isEmpty()) sb.append("(কোনো স্যাম্পল নেই)\n")
                                recentStacks.forEach { sb.append("$it\n\n") }
                            }
                            try {
                                val rm = findRealMain()
                                val st = rm?.stackTrace
                                sb.append("\n\"realMain\" সরাসরি: name=${rm?.name} state=${rm?.state} frames=${st?.size ?: -1}\n")
                                for (el in (st ?: emptyArray()).take(60)) sb.append("  at $el\n")
                            } catch (e: Exception) {
                                sb.append("\nrealMain dump ব্যর্থ: $e\n")
                            }
                            reportFile.writeText(sb.toString().take(12000))
                            state[0] = SystemClock.uptimeMillis()
                        }
                    } catch (_: Exception) { }
                }
            }, "AnrWatchdog").apply { isDaemon = true }.start()
        } catch (e: Exception) {
            Log.w("ClonerApp", "watchdog start failed", e)
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // ANR watchdog: main thread 6 সেকেন্ডের বেশি আটকে থাকলে
        // সব thread-এর stack ফাইলে লিখে রাখে, যাতে hang-এর কারণ বোঝা যায়।
        startAnrWatchdog()

        try {
            BlackBoxCore.get().doAttachBaseContext(base, object : ClientConfiguration() {
                override fun getHostPackageName(): String = packageName
                override fun isEnableDaemonService(): Boolean = true
                override fun requestInstallPackage(file: File?, userId: Int): Boolean = false
            })
        } catch (e: Exception) {
            Log.e("ClonerApp", "BlackBox attach failed", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Crash রিপোর্টার: virtual অ্যাপ crash করলে stack trace ফাইলে রেখে দেয়,
        // যাতে UI থেকে দেখে কারণ বোঝা যায়। (প্রসেস অনুযায়ী আলাদা ফাইল)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val proc = runCatching {
                    java.io.File("/proc/self/cmdline").readText()
                        .replace("\u0000", " ").trim()
                        .replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "?" }
                }.getOrDefault("?")
                File(cacheDir, "last_crash_$proc.txt").writeText(
                    "${Date()} [$proc]\nThread: ${thread.name}\n${sw}"
                )
            } catch (_: Exception) { }
            previousHandler?.uncaughtException(thread, throwable)
        }
        try {
            BlackBoxCore.get().doCreate()
            Engine.current = BlackBoxCloneEngine()
            Log.i("ClonerApp", "BlackBox engine ready")
        } catch (e: Exception) {
            Log.e("ClonerApp", "BlackBox init failed, using NoEngine", e)
            Engine.current = NoEngine
        }
    }
}
