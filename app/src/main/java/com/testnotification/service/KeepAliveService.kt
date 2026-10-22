package com.testnotification.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import androidx.core.content.ContextCompat
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.testnotification.MainActivity
import com.testnotification.R
import com.testnotification.receiver.AlarmReceiver

/**
 * KeepAliveService — ตัวยึดโปรเซสแบบเบา (ไม่ถือ WakeLock ค้าง ไม่มีลูปในโปรเซส)
 *
 * หลักการ (event-driven):
 * - งานจริงเริ่มตอนมีแจ้งเตือนของแอพที่เลือกไว้เท่านั้น (TestNotificationService
 *   ถูกระบบปลุกเอง) และถือ WakeLock สั้นๆ เฉพาะช่วงส่ง API
 * - service นี้ทำหน้าที่ให้โปรเซสมี priority สูง (foreground) กัน OEM ฆ่า
 *   + ตั้ง alarm one-shot ทุก 1 นาที + ฟังเหตุการณ์ เน็ตกลับมา/จอเปิดปิด -> OrderWatcher.tick()
 *   (ตรวจ/ซ่อม listener, แจ้งออเดอร์ใหม่, อ่านแจ้งเตือนที่ค้าง)
 * - startService() จากเบื้องหลังอาจ throw บน Android 8+/12+ → ครอบ try/catch
 *   (crash = ลาก NotificationListener ตายไปด้วย)
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG               = "KeepAliveService"
        private const val CHANNEL_ID        = "payment_keepalive"
        private const val NOTIF_ID          = 1001

        // alarm one-shot ทุก 1 นาที: ตรวจ listener + เช็กออเดอร์ใหม่ + อ่านแจ้งเตือนที่ค้าง (ทำเฉพาะตอนมีเน็ต)
        // ปรับค่านี้ได้ — ยาวขึ้น = ประหยัดแบตกว่า แต่แจ้งออเดอร์ใหม่ช้าลง
        private const val ALARM_INTERVAL_MS = 60 * 1000L
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

    // ตัวกระตุ้นตามเหตุการณ์: เน็ตกลับมา / จอเปิด-ปิด -> OrderWatcher.tick (ไม่มีเน็ตจะข้ามเอง)
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            OrderWatcher.tick(applicationContext, "network")
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            OrderWatcher.tick(applicationContext, "screen")
        }
    }
    private var triggersRegistered = false

    private fun registerTriggers() {
        if (triggersRegistered) return
        try {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(netCallback)
            ContextCompat.registerReceiver(
                this, screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            triggersRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "registerTriggers failed: ${e.message}")
        }
    }

    private fun unregisterTriggers() {
        if (!triggersRegistered) return
        triggersRegistered = false
        try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback) } catch (_: Exception) {}
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        scheduleAlarm(applicationContext)   // ← AlarmManager backup (one-shot)
        registerTriggers()
        Log.d(TAG, "created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ถูกปลุกโดย AlarmManager / BootReceiver / เปิดแอพ
        scheduleAlarm(applicationContext)   // alarm เป็นแบบ one-shot — ตั้งรอบถัดไปเสมอ
        reconnectNotificationListener("onStartCommand")
        OrderWatcher.tick(applicationContext, "onStartCommand")
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
        unregisterTriggers()
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
