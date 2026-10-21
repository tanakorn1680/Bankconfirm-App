package com.testnotification.service

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * ListenerHealth — ตรวจและซ่อม NotificationListenerService ที่หลุด bind
 *
 * ปัญหาเดิม: ใช้ไปนานๆ listener เงียบ ต้องไปปิด/เปิดสิทธิ์ใน Settings ถึงกลับมา
 * สาเหตุ: โปรเซสถูกฆ่า/crash แล้วระบบไม่ rebind ให้ และ requestRebind() อย่างเดียว
 *         มักไม่พอ (ระบบมองว่า listener ยัง "enabled" อยู่แล้วเมินคำขอ)
 * วิธีแก้: ถ้า access ถูก grant แล้ว แต่ listener ไม่ connected → สลับ enable/disable
 *         ของ component ผ่าน PackageManager ซึ่งบังคับให้ระบบ bind ใหม่
 *         (เทียบเท่าการปิด/เปิดสิทธิ์ใน Settings ด้วยมือ)
 *
 * ไม่สลับเมื่อ listener ปกติ (connected=true) เพื่อไม่ให้ notification หายระหว่างช่วงสลับ
 */
object ListenerHealth {

    private const val TAG = "ListenerHealth"

    // รอให้ระบบ bind เองก่อน แล้วค่อยสลับ component ถ้ายังไม่ connect
    private const val RECHECK_DELAY_MS = 5_000L

    // กันสลับถี่เกินไป (loop)
    private const val MIN_TOGGLE_INTERVAL_MS = 60_000L

    @Volatile var connected: Boolean = false

    // instance ของ service ที่ใช้งานอยู่ — กัน onDestroy ของตัวเก่าเขียนทับสถานะของตัวใหม่
    @Volatile var owner: Any? = null

    @Volatile private var checkPending = false
    @Volatile private var lastToggleMs = -MIN_TOGGLE_INTERVAL_MS

    private val handler = Handler(Looper.getMainLooper())

    fun component(ctx: Context): ComponentName =
        ComponentName(ctx.applicationContext, TestNotificationService::class.java)

    /** ผู้ใช้ให้สิทธิ์ Notification Access กับ listener ตัวนี้อยู่หรือไม่ */
    fun isAccessGranted(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(
            ctx.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        val me = component(ctx)
        return flat.split(":").any { ComponentName.unflattenFromString(it) == me }
    }

    /**
     * เรียกได้จากที่ไหนก็ได้ (Service/Activity/Receiver) — ถ้า listener ไม่ connected
     * จะลอง requestRebind ก่อน แล้วถ้ายังไม่ connect ใน 5 วินาที จะสลับ component
     */
    fun ensureBound(ctx: Context, reason: String) {
        val app = ctx.applicationContext
        try {
            if (!isAccessGranted(app)) return      // ผู้ใช้ยังไม่ให้สิทธิ์ — ทำอะไรไม่ได้
            if (connected) return                  // ปกติดี
            if (checkPending) return               // กำลังรอตรวจอยู่แล้ว
            checkPending = true

            Log.w(TAG, "listener ไม่ connected ($reason) — requestRebind")
            try {
                NotificationListenerService.requestRebind(component(app))
            } catch (e: Exception) {
                Log.w(TAG, "requestRebind failed: ${e.message}")
            }

            handler.postDelayed({
                checkPending = false
                if (!connected && isAccessGranted(app)) forceRebind(app, reason)
            }, RECHECK_DELAY_MS)
        } catch (e: Exception) {
            checkPending = false
            Log.w(TAG, "ensureBound error: ${e.message}")
        }
    }

    /** สลับ enable/disable component เพื่อบังคับให้ระบบ bind listener ใหม่ */
    fun forceRebind(ctx: Context, reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastToggleMs < MIN_TOGGLE_INTERVAL_MS) return
        lastToggleMs = now

        val app = ctx.applicationContext
        val cn = component(app)
        try {
            val pm = app.packageManager
            pm.setComponentEnabledSetting(
                cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
            )
            NotificationListenerService.requestRebind(cn)
            Log.w(TAG, "toggled component to force rebind ($reason)")
        } catch (e: Exception) {
            Log.e(TAG, "forceRebind failed: ${e.message}")
        }
    }
}
