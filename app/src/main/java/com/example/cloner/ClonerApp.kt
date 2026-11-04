package com.example.cloner

import android.app.Application
import android.content.Context
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.configuration.ClientConfiguration
import java.io.File

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
