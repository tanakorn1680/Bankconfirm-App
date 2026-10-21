package com.testnotification.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.testnotification.MainActivity
import com.testnotification.R
import com.testnotification.receiver.AlarmReceiver

/**
 * KeepAliveService — hardened สำหรับ MIUI/HyperOS/Samsung
 *
 * แก้ปัญหา:
 * 1. WakeLock expire ก่อน watchdog renew → ใช้ timeout 65 นาที + renew ทุก 30 นาที
 * 2. onDestroy() ถูก block โดย OS → AlarmManager backup ทุก 15 นาที
 * 3. Coroutine scope ค้างหลัง service destroy → cancel ใน onDestroy()
 * 4. startService() จากเบื้องหลัง (onDestroy/onTaskRemoved) throw บน Android 8+/12+
 *    แล้วทำให้โปรเซส crash (ลาก NotificationListener ตายไปด้วย) → ครอบ try/catch
 * 5. NotificationListener หลุด bind แล้วไม่กลับมา → ListenerHealth.ensureBound()
 *    (สลับ enable/disable component) ทุกครั้งที่ watchdog/alarm/start ทำงาน
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG               = "KeepAliveService"
        private const val CHANNEL_ID        = "payment_keepalive"
        private const val NOTIF_ID          = 1001
        private const val WAKE_TAG          = "com.testnotification:keepalive"

        // watchdog renew wakelock + rebind ทุก 30 นาที
        private const val WATCHDOG_MS       = 30 * 60 * 1000L

        // wakelock timeout = 65 นาที (> WATCHDOG_MS * 2 เผื่อ Doze delay)
        private const val WAKE_TIMEOUT_MS   = 65 * 60 * 1000L

        // AlarmManager backup — ทำให้ OS ปลุก service ทุก 15 นาที
        private const val ALARM_INTERVAL_MS = 15 * 60 * 1000L
        private const val ALARM_REQUEST     = 9001

        /**
         * ตั้ง alarm ครั้งเดียว — AlarmReceiver จะเรียกซ้ำเพื่อตั้งรอบถัดไป
         * ใช้ setAndAllowWhileIdle เพราะ setRepeating (inexact) ถูกเลื่อนยาวมากตอนเครื่อง Doze
         * ทำให้ watchdog ไม่ทำงานตอนกลางคืน/เครื่องนิ่งนานๆ
         */
        fun scheduleAlarm(context: android.content.Context) {
            try {
                val am = context.getSystemService(AlarmManager::class.java)
                val pi = alarmIntent(context)
                // cancel อันเก่าก่อนเสมอ — ป้องกัน duplicate
                am.cancel(pi)
                am.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS,
                    pi
                )
                Log.d(TAG, "alarm scheduled in ${ALARM_INTERVAL_MS / 60000} min")
            } catch (e: Exception) {
                Log.w(TAG, "scheduleAlarm failed: ${e.message}")
            }
        }

        fun cancelAlarm(context: android.content.Context) {
            try {
                val am = context.getSystemService(AlarmManager::class.java)
                am.cancel(alarmIntent(context))
                Log.d(TAG, "alarm cancelled")
            } catch (e: Exception) {
                Log.w(TAG, "cancelAlarm failed: ${e.message}")
            }
        }

        private fun alarmIntent(context: android.content.Context): PendingIntent =
            PendingIntent.getBroadcast(
                context, ALARM_REQUEST,
                Intent(context, AlarmReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    private val watchdog = object : Runnable {
        override fun run() {
            Log.d(TAG, "watchdog ping")
            renewWakeLock()
            reconnectNotificationListener("watchdog")
            handler.postDelayed(this, WATCHDOG_MS)
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
        scheduleAlarm(applicationContext)   // ← AlarmManager backup
        handler.post(watchdog)
        Log.d(TAG, "created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ถูกปลุกโดย AlarmManager หรือ BootReceiver — ตรวจสอบ wakelock
        if (wakeLock == null || wakeLock?.isHeld == false) acquireWakeLock()
        scheduleAlarm(applicationContext)   // alarm เป็นแบบ one-shot — ตั้งรอบถัดไปเสมอ
        reconnectNotificationListener("onStartCommand")
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.w(TAG, "task removed — restarting")
        safeRestartSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "destroyed — restarting")
        handler.removeCallbacks(watchdog)
        releaseWakeLock()
        // AlarmManager จะ restart อยู่แล้ว แต่ startService เพิ่มเผื่อ OS อนุญาต
        safeRestartSelf()
    }

    /**
     * startService จากเบื้องหลังอาจ throw (IllegalStateException / ForegroundServiceStartNotAllowed)
     * ถ้าไม่จับ โปรเซสจะ crash และลาก NotificationListener ตายไปด้วย
     */
    private fun safeRestartSelf() {
        try {
            startService(Intent(applicationContext, KeepAliveService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "restart self not allowed: ${e.message} — รอ AlarmManager ปลุกแทน")
        }
    }

    // =========================================================================
    // WakeLock — timeout 65 นาที ป้องกัน Doze delay ทำ renew ไม่ทัน
    // =========================================================================

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKE_TIMEOUT_MS)
        }
        Log.d(TAG, "wakelock acquired ${WAKE_TIMEOUT_MS / 60000} min")
    }

    private fun renewWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
                it.acquire(WAKE_TIMEOUT_MS)
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

    private fun reconnectNotificationListener(reason: String) {
        // ถ้า listener ปกติ (connected) จะไม่ทำอะไร — ถ้าหลุดจะ requestRebind แล้วสลับ component
        ListenerHealth.ensureBound(applicationContext, "KeepAlive:$reason")
    }

    // =========================================================================
    // Notification
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
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Payment Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "แสดงสถานะการทำงานพื้นหลัง"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(channel)
    }
}
