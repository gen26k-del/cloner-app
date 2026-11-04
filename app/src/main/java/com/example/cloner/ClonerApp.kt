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
            // ticker main thread-এ চলে, তাই Thread.currentThread() দিয়েই
            // আসল main thread ধরা হবে (Looper.getMainLooper().thread ভুল হতে পারে)
            val mainThreadRef = arrayOfNulls<Thread>(1)
            val state = longArrayOf(SystemClock.uptimeMillis())
            // main thread আটকানোর ঠিক আগের অবস্থানগুলো ধরে রাখার জন্য ring buffer
            val recentStacks = ArrayDeque<String>()
            val ticker = object : Runnable {
                override fun run() {
                    if (mainThreadRef[0] == null) {
                        mainThreadRef[0] = Thread.currentThread()
                    }
                    val mt = mainThreadRef[0]
                    try {
                        val st = mt?.stackTrace
                        if (st != null && st.isNotEmpty()) {
                            val top = st.take(8).joinToString("\n") { "  at $it" }
                            synchronized(recentStacks) {
                                recentStacks.addLast(top)
                                if (recentStacks.size > 5) recentStacks.removeFirst()
                            }
                        }
                    } catch (_: Exception) { }
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
                            val procName = runCatching {
                                java.io.File("/proc/self/cmdline").readText()
                                    .replace("\u0000", " ").trim()
                            }.getOrDefault("?")
                            val sb = StringBuilder("ANR ধরা পড়েছে: ${Date()} [$procName]\n")
                            // ১. আটকানোর আগের ১০ সেকেন্ডে main thread কোথায় ছিল
                            sb.append("\n--- main thread-এর সাম্প্রতিক অবস্থান ---\n")
                            synchronized(recentStacks) {
                                if (recentStacks.isEmpty()) sb.append("(কোনো স্যাম্পল নেই)\n")
                                recentStacks.forEachIndexed { i, s ->
                                    sb.append("[${i}] $s\n")
                                }
                            }
                            // ২. main thread-এর সরাসরি dump
                            try {
                                val mt = mainThreadRef[0]
                                val st = mt?.stackTrace
                                sb.append("\n\"main\" সরাসরি: state=${mt?.state}, frames=${st?.size ?: -1}\n")
                                for (el in (st ?: emptyArray()).take(60)) sb.append("  at $el\n")
                            } catch (e: Exception) {
                                sb.append("\nmain সরাসরি dump ব্যর্থ: $e\n")
                            }
                            // ৩. সব thread-এর নাম ও অবস্থা
                            sb.append("\n--- সব thread ---\n")
                            try {
                                for (t in Thread.getAllStackTraces().keys) {
                                    sb.append("${t.name} : ${t.state}\n")
                                }
                            } catch (e: Exception) {
                                sb.append("তালিকা ব্যর্থ: $e\n")
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
