package com.testnotification.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationCompat
import com.testnotification.MainActivity
import com.testnotification.R

/**
 * KeepAliveService — hardened สำหรับ MIUI/HyperOS
 *
 * แก้ปัญหา:
 * 1. notification ปัดทิ้งได้    → FOREGROUND_SERVICE_IMMEDIATE + setOngoing
 * 2. หยุดทำงานหลัง 1 วัน       → watchdog loop requestRebind ทุก 30 นาที
 *                                  + WakeLock renew อัตโนมัติก่อน timeout
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG              = "KeepAliveService"
        private const val CHANNEL_ID       = "payment_keepalive"
        private const val NOTIF_ID         = 1001
        private const val WAKE_TAG         = "com.testnotification:keepalive"

        // watchdog ping ทุก 30 นาที — rebind + renew wakelock
        private const val WATCHDOG_INTERVAL = 30 * 60 * 1000L

        // wakelock timeout ต่อครั้ง = 35 นาที (มากกว่า interval นิดนึง)
        private const val WAKE_TIMEOUT      = 35 * 60 * 1000L
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val handler  = Handler(Looper.getMainLooper())

    // watchdog loop — ทำงานทุก WATCHDOG_INTERVAL
    private val watchdog = object : Runnable {
        override fun run() {
            Log.d(TAG, "watchdog ping")
            renewWakeLock()
            reconnectNotificationListener()
            handler.postDelayed(this, WATCHDOG_INTERVAL)
        }
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        acquireWakeLock()
        handler.post(watchdog)   // เริ่ม watchdog ทันที
        Log.d(TAG, "created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        reconnectNotificationListener()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.w(TAG, "task removed — restarting")
        startService(Intent(applicationContext, KeepAliveService::class.java))
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "destroyed — restarting")
        handler.removeCallbacks(watchdog)
        releaseWakeLock()
        startService(Intent(applicationContext, KeepAliveService::class.java))
    }

    // =========================================================================
    // WakeLock
    // =========================================================================

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKE_TIMEOUT)
        }
        Log.d(TAG, "wakelock acquired ${WAKE_TIMEOUT / 60000} min")
    }

    /** ต่ออายุ wakelock ก่อน timeout — เรียกจาก watchdog ทุก 30 นาที */
    private fun renewWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
                it.acquire(WAKE_TIMEOUT)
                Log.d(TAG, "wakelock renewed")
            } ?: acquireWakeLock()
        } catch (e: Exception) {
            Log.w(TAG, "wakelock renew error: ${e.message}")
            acquireWakeLock()
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        } catch (e: Exception) {
            Log.w(TAG, "wakelock release error: ${e.message}")
        }
    }

    // =========================================================================
    // Reconnect NotificationListener
    // =========================================================================

    private fun reconnectNotificationListener() {
        try {
            val component = ComponentName(this, TestNotificationService::class.java)
            NotificationListenerService.requestRebind(component)
            Log.d(TAG, "requestRebind sent")
        } catch (e: Exception) {
            Log.w(TAG, "requestRebind failed: ${e.message}")
        }
    }

    // =========================================================================
    // Notification — ป้องกันปัดทิ้ง
    // =========================================================================

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Payment")
            .setContentText("กำลังตรวจสอบการชำระเงิน...")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openApp)
            .setOngoing(true)               // ปัดทิ้งไม่ได้ (มาตรฐาน)
            .setSilent(true)                // ไม่มีเสียง
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // MIUI: foreground service notification ต้องระบุ behavior
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return  // สร้างแล้ว ข้าม
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Payment Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description    = "แสดงสถานะการทำงานพื้นหลัง"
            setShowBadge(false)
            // MIUI: ป้องกัน OS ซ่อน channel อัตโนมัติ
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(channel)
    }
}
