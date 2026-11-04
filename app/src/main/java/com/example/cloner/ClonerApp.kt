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

/** শেষ crash/ANR-এর রিপোর্ট ফাইলের নাম (cache dir-এ) */
const val CRASH_REPORT_FILE = "last_crash.txt"

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
            val mainHandler = Handler(Looper.getMainLooper())
            val state = longArrayOf(SystemClock.uptimeMillis())
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
                        Thread.sleep(3000)
                        val blockedFor = SystemClock.uptimeMillis() - state[0]
                        if (blockedFor > 6000) {
                            val sb = StringBuilder("ANR ধরা পড়েছে: ${Date()}\n")
                            val all = Thread.getAllStackTraces()
                            // সবার আগে MAIN thread — এটাই আসল আটকে থাকা জায়গা
                            val main = all.keys.find { it.name == "main" }
                            if (main != null) {
                                sb.append("\n\"main\" state=${main.state}\n")
                                for (el in (all[main] ?: emptyArray()).take(60)) {
                                    sb.append("  at $el\n")
                                }
                            } else {
                                sb.append("\nmain thread পাওয়া যায়নি!\n")
                            }
                            // তারপর RUNNABLE/BLOCKED thread (অলস WAITING বাদ)
                            for ((t, stack) in all) {
                                if (t.name == "main") continue
                                if (t.name.contains("watchdog", ignoreCase = true)) continue
                                if (t.state == Thread.State.WAITING ||
                                    t.state == Thread.State.TIMED_WAITING
                                ) continue
                                sb.append("\n\"${t.name}\" state=${t.state}\n")
                                for (el in stack.take(20)) sb.append("  at $el\n")
                            }
                            File(cacheDir, CRASH_REPORT_FILE)
                                .writeText(sb.toString().take(8000))
                            // আবার লেখা এড়াতে টাইমার রিসেট
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
        // যাতে UI থেকে দেখে কারণ বোঝা যায়।
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                File(cacheDir, CRASH_REPORT_FILE).writeText(
                    "${Date()}\nThread: ${thread.name}\n${sw}"
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
