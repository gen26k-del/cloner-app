package com.example.cloner

import android.app.Application
import android.content.Context
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.configuration.ClientConfiguration
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/** শেষ crash-এর রিপোর্ট ফাইলের নাম (cache dir-এ) */
const val CRASH_REPORT_FILE = "last_crash.txt"

/**
 * অ্যাপের Application ক্লাস — BlackBox ভার্চুয়াল ইঞ্জিনের দুই ধাপের init এখানে হয়।
 * ক্রম বাধ্যতামূলক: আগে doAttachBaseContext(), তারপর onCreate()-এ doCreate()।
 */
class ClonerApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
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
