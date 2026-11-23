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
     * নতুন রিপোর্ট আসলে আগের সব রিপোর্ট মুছে ফেলো — শুধু নতুনটাই থাকবে।
     * (ব্যবহারকারী: আগের রিপোর্ট যাতে না দেয়, নতুন রিপোর্ট আসলে আগেরটা মুছে যাবে।)
     */
    private fun clearOldCrashReports() {
        try {
            cacheDir.listFiles { f ->
                f.name.startsWith("last_crash") && f.name.endsWith(".txt")
            }?.forEach { runCatching { it.delete() } }
        } catch (_: Exception) { }
    }

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
            // v1.0.150: প্রতি stall episode-এ MainStall লাইন একবারই লিখো
            // v1.0.155: guest (clone) প্রসেস = :pN stub — সেখানে threshold
            // কম (2s) আর স্যাম্পলিং ঘন। .154-এর রিপোর্টে 4s threshold
            // guest-এর stall প্রায় ধরতেই পারেনি (পুরো window-তে মাত্র
            // একটা 4938ms), অথচ ব্যবহারকারীর অভিজ্ঞতায় "isn't
            // responding" বারবার এসেছে — হালকা কাজ চলে, ভারী launch
            // আটকায় এমন প্যাটার্ন 2–4s-এর block-এ লুকিয়ে থাকে।
            val isGuestProc155 = procName.startsWith("$packageName:p")
            val stallThreshold155 = if (isGuestProc155) 2000L else 4000L
            val tickerInterval155 = if (isGuestProc155) 500L else 2000L
            val watchdogSleep155 = if (isGuestProc155) 1000L else 3000L
            // প্রতি episode-এ নেওয়া stack স্যাম্পলের সংখ্যা (সর্বোচ্চ ৬)
            var stallSamples155 = 0
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
                    mainHandler.postDelayed(this, tickerInterval155)
                }
            }
            mainHandler.post(ticker)
            Thread({
                while (true) {
                    try {
                        // স্যাম্পলিং বন্ধ — getAllStackTraces() thread suspend করে hang ডেকে আনতে পারে
                        // শুধু ticker-এর মাধ্যমে blockage detect করো
                        Thread.sleep(watchdogSleep155)
                        val blockedFor = SystemClock.uptimeMillis() - state[0]
                        // v1.0.150: ANR প্রমাণ — main thread ৪ সেকেন্ডের
                        // বেশি আটকে থাকলেই init trace-এ এক লাইন (প্রতি
                        // stall episode-এ একবার)। সিস্টেমের ANR ডায়ালগ
                        // (~৫s input timeout) অনেক সময় watchdog-এর ৮s
                        // crash-file লেখার আগেই আসে, বা প্রসেস তার আগেই
                        // মরে যায় — তখন রিপোর্টে কোনো প্রমাণই থাকে না
                        // (2026-11-23 13:12-র "CloneMe isn't responding"
                        // ANR-এ ঠিক এটাই হয়েছিল: কোনো crash file ওঠেনি)।
                        // লাইনটা স্মার্ট রিপোর্টের ডায়াগনসিস অংশে
                        // MainStall keyword-এ ওঠে। এটি শুধু ট্রেস —
                        // ANR-এর চিকিৎসা নয়।
                        // v1.0.155: episode চলাকালীন বারবার স্যাম্পল —
                        // প্রথম স্যাম্পল MainStall লাইন + stack, পরেরগুলো
                        // "== sample N ==" হেডার + stack (সর্বোচ্চ ৬টা)।
                        // প্রতিটি স্যাম্পলে fg ট্যাগ: এই প্রসেসে সর্বশেষ
                        // resume হওয়া activity (BActivityThread-তে নোট
                        // করা), না থাকলে launch-রত activity। .152-এর
                        // একবারের স্যাম্পল stall-এর শুরুটা দেখাত, কিন্তু
                        // দীর্ঘ/বারবার stall-এ main thread কোথায় ঘোরে
                        // তা ধরা যেত না।
                        if (blockedFor > stallThreshold155 && stallSamples155 < 6) {
                            val firstSample155 = stallSamples155 == 0
                            stallSamples155++
                            runCatching {
                                val fg155 = runCatching {
                                    top.niunaijun.blackbox.app.BActivityThread.getForegroundActivity155()
                                }.getOrNull()
                                val launching155 = if (fg155 == null) runCatching {
                                    top.niunaijun.blackbox.app.BActivityThread.getPendingLaunch155()
                                }.getOrNull() else null
                                val tag155 = when {
                                    fg155 != null -> " fg=$fg155"
                                    launching155 != null -> " launching=$launching155"
                                    else -> ""
                                }
                                val line = StringBuilder()
                                if (firstSample155) {
                                    line.append("${Date()} [$procName] MainStall: main thread blocked for ${blockedFor}ms$tag155\n")
                                } else {
                                    line.append("MainStallStack: == sample $stallSamples155 blocked ${blockedFor}ms$tag155 ==\n")
                                }
                                // v1.0.152: stall-এর মুহূর্তে main thread
                                // ঠিক কোন কোডে আটকে আছে — তার stack-এর
                                // প্রথম কয়েকটি ফ্রেম একই সঙ্গে লিখো।
                                // শুধু stall episode-এই স্যাম্পল হয়
                                // (Looper-এর main thread সরাসরি —
                                // getAllStackTraces নয়), তাই stall না
                                // থাকলে overhead শূন্য।
                                try {
                                    val st = Looper.getMainLooper().thread.stackTrace
                                    for (el in st.take(12)) {
                                        line.append("MainStallStack: at $el\n")
                                    }
                                } catch (_: Exception) { }
                                java.io.File(cacheDir, "init_trace.txt").appendText(line.toString())
                            }
                        }
                        if (blockedFor <= stallThreshold155) stallSamples155 = 0
                        if (blockedFor > 8000) {
                            // ANR লিখো শুধু যখন নিশ্চিত যে main thread আসলেই আটকে আছে:
                            // main thread পাওয়া গেছে, stack খালি নয়, আর উপরের ফ্রেম
                            // nativePollOnce (idle) নয়। main thread পাওয়া না গেলে বা
                            // stack খালি থাকলে নিশ্চিত হওয়া যায় না — তখন রিপোর্ট লিখো না।
                            // (আগের ফিল্টার শুধু idle চিনত; পাওয়া না গেলে লিখে ফেলত — তাই
                            // একই ভুয়া ANR বারবার আসত।)
                            val rmStuck = try {
                                val rm = findRealMain()
                                val st = rm?.stackTrace
                                rm != null && !st.isNullOrEmpty() &&
                                    !(st.first().className == "android.os.MessageQueue" &&
                                        st.first().methodName == "nativePollOnce")
                            } catch (_: Exception) { false }
                            if (!rmStuck) {
                                state[0] = SystemClock.uptimeMillis()
                            } else {
                            val sb = StringBuilder("ANR ধরা পড়েছে: ${Date()} [$procName]\n")
                            // init trace ফাইল থাকলে যোগ করো — ঠিক কোথায় আটকেছে দেখাবে
                            // শেষের অংশ নাও (সাম্প্রতিক প্রসেসের এন্ট্রি)
                            try {
                                val traceFile = File(cacheDir, "init_trace.txt")
                                if (traceFile.exists()) {
                                    sb.append("\n--- init trace (শেষাংশ) ---\n")
                                    sb.append(traceFile.readText().takeLast(4000))
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
                            // নতুন রিপোর্ট লেখার আগে আগের সব রিপোর্ট মুছে ফেলো
                            clearOldCrashReports()
                            reportFile.writeText(sb.toString().take(12000))
                            state[0] = SystemClock.uptimeMillis()
                            }
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
        } catch (e: Throwable) {
            // v1.0.152: Exception নয়, Throwable ধরো — cold start-এ
            // (বিশেষ করে broadcast-এ জাগা :black প্রসেসে) init-এর ভেতর
            // কোনো Error (যেমন UnsatisfiedLinkError / OOM) ছিটকে গেলে
            // আগে প্রসেসটাই মরে যেত (INIT_FAILURE exit), এখন degrade
            // করে বেঁচে থাকবে আর BlackInit লাইনে প্রমাণ থাকবে।
            Log.e("ClonerApp", "BlackBox attach failed", e)
            runCatching {
                java.io.File(cacheDir, "init_trace.txt").appendText(
                    "${Date()} BlackInit: attachBaseContext failed: $e\n"
                )
            }
        }
    }

    // v1.0.119: হুক-যাচাই ফাইলে এক লাইন যোগ করো (৪KB ছাড়ালে পুরনো
    // অর্ধেক ছেঁটে ফেলো) — diag-এর grouping-এ REGISTERED/FIRED লাইন
    // চাপা পড়ে যায়, তাই আলাদা ফাইলে প্রমাণ রাখো।
    private fun appendHookCheck(line: String) {
        try {
            val f = java.io.File(cacheDir, "hook_check.txt")
            if (f.exists() && f.length() > 4000) {
                f.writeText(f.readText().takeLast(2000))
            }
            f.appendText(line + "\n")
        } catch (_: Exception) { }
    }

    override fun onCreate() {
        super.onCreate()
        // v1.0.115: শাটডাউন হুক — প্রসেস নিজে থেকে clean exit (System.exit)
        // করলে এই লাইন ট্রেসে পড়বে; SIGKILL-এ পড়বে না। এতে বোঝা যাবে
        // renderer নিজে মরছে নাকি বাইরে থেকে মারা হচ্ছে।
        try {
            Runtime.getRuntime().addShutdownHook(Thread {
                try {
                    val proc = java.io.File("/proc/self/cmdline").readText()
                        .replace(" ", " ").trim()
                    java.io.File(cacheDir, "init_trace.txt").appendText(
                        "${java.util.Date()} [$proc] SHUTDOWN HOOK FIRED — clean VM exit, not SIGKILL\n"
                    )
                    appendHookCheck("${java.util.Date()} [$proc] SHUTDOWN HOOK FIRED")
                } catch (_: Exception) { }
            })
            // v1.0.118: হুক বসেছে তার প্রমাণ — পরের রিপোর্টে এই লাইন
            // থাকলে অথচ HOOK FIRED না থাকলে EXIT_SELF মানে নেটিভ exit।
            val proc0 = java.io.File("/proc/self/cmdline").readText()
                .replace(" ", " ").trim()
            java.io.File(cacheDir, "init_trace.txt").appendText(
                "${java.util.Date()} [$proc0] SHUTDOWN HOOK REGISTERED\n"
            )
            appendHookCheck("${java.util.Date()} [$proc0] SHUTDOWN HOOK REGISTERED")
        } catch (_: Exception) { }
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
                // নতুন রিপোর্ট লেখার আগে আগের সব রিপোর্ট মুছে ফেলো
                clearOldCrashReports()
                File(cacheDir, "last_crash_$proc.txt").writeText(
                    "${Date()} [$proc]\nThread: ${thread.name}\n${sw}"
                )
            } catch (_: Exception) { }
            previousHandler?.uncaughtException(thread, throwable)
        }
        try {
            BlackBoxCore.get().doCreate()
            // init trace
            try {
                val traceFile = java.io.File(cacheDir, "init_trace.txt")
                val proc = java.io.File("/proc/self/cmdline").readText()
                    .replace("\u0000", " ").trim()
                traceFile.appendText("${java.util.Date()} [$proc] ClonerApp.onCreate doCreate done\n")
            } catch (_: Exception) { }
            Engine.current = BlackBoxCloneEngine()
            Log.i("ClonerApp", "BlackBox engine ready")
        } catch (e: Throwable) {
            // v1.0.152: উপরের attach-এর মতোই — doCreate-এর Error-ও
            // প্রসেস না মেরে NoEngine-এ নামিয়ে আনো + BlackInit ট্রেস।
            Log.e("ClonerApp", "BlackBox init failed, using NoEngine", e)
            runCatching {
                java.io.File(cacheDir, "init_trace.txt").appendText(
                    "${Date()} BlackInit: doCreate failed: $e\n"
                )
            }
            Engine.current = NoEngine
        }
        // নোটিফিকেশন বাঁচিয়ে রাখার জন্য keep-alive সার্ভিস (শুধু মেইন প্রসেসে)
        try {
            val proc = java.io.File("/proc/self/cmdline").readText()
                .replace("\u0000", " ").trim()
            if (proc == packageName) {
                val svc = android.content.Intent(this, KeepAliveService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(svc)
                } else {
                    startService(svc)
                }
            }
        } catch (e: Exception) {
            Log.e("ClonerApp", "KeepAlive start failed", e)
        }
    }
}
