package com.example.cloner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

// ভার্চুয়াল GMS / FCM পুশ নোটিফিকেশন বাঁচিয়ে রাখার জন্য ফোরগ্রাউন্ড সার্ভিস।
// Parallel Space-ও একই কারণে স্থায়ী নোটিফিকেশন রাখে।
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channelId = "cloneme_keepalive"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId,
                "CloneMe ব্যাকগ্রাউন্ড",
                NotificationManager.IMPORTANCE_MIN
            )
            ch.description = "ক্লোন অ্যাপের নোটিফিকেশন চালু রাখে"
            nm.createNotificationChannel(ch)
        }
        val nb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val n = nb
            .setContentTitle("CloneMe চলছে")
            .setContentText("ক্লোন অ্যাপের নোটিফিকেশন সক্রিয় রাখা হয়েছে")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
        try {
            startForeground(1001, n)
        } catch (e: Exception) {
            Log.e("KeepAlive", "startForeground failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
}
