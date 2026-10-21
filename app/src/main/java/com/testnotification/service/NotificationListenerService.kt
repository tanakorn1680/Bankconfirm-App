package com.testnotification.service

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.testnotification.data.AppState
import com.testnotification.data.NotificationLog
import com.testnotification.network.ApiClient
import com.testnotification.util.AmountParser
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * TestNotificationService
 *
 * Fix: serviceScope ถูก cancel ใน onDestroy() ป้องกัน coroutine ค้างหลัง restart
 * Fix: exception ใน callback/coroutine ต้องไม่ทำให้โปรเซส crash (crash ซ้ำ = ระบบเลิก bind listener)
 * Fix: รายงานสถานะ connected ให้ ListenerHealth เพื่อซ่อมตัวเองเมื่อหลุด bind
 */
class TestNotificationService : NotificationListenerService() {

    companion object {
        private const val TAG = "TestNotifService"

        private val BANK_PACKAGES = mapOf(
            "com.kasikorn.retail.mbanking.wap" to "kbank",
            "th.co.truemoney.wallet"            to "truemoney",
            "com.truemoney.android.wallet"      to "truemoney",
        )

        // SHOW_ALL = false ในโปรดักชัน — แสดงเฉพาะ BANK_PACKAGES เพื่อประหยัด memory
        const val SHOW_ALL = false
    }

    // กัน exception ที่หลุดจาก coroutine ไม่ให้ crash ทั้งโปรเซส
    private val scopeErrorHandler = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "coroutine error: ${e.message}", e)
    }

    private fun newScope() =
        CoroutineScope(Dispatchers.IO + SupervisorJob() + scopeErrorHandler)

    // สร้าง scope ใหม่ทุกครั้ง onCreate() เพื่อให้ cancel ใน onDestroy() ได้สะอาด
    private var serviceScope = newScope()

    override fun onCreate() {
        super.onCreate()
        serviceScope.cancel()
        serviceScope = newScope()
        ListenerHealth.owner = this
        Log.d(TAG, "Service created — SHOW_ALL=$SHOW_ALL")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        ListenerHealth.owner = this
        ListenerHealth.connected = true
        Log.d(TAG, "Listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (ListenerHealth.owner === this) ListenerHealth.connected = false
        Log.w(TAG, "Listener disconnected — requesting rebind")
        try {
            requestRebind(
                android.content.ComponentName(this, TestNotificationService::class.java)
            )
        } catch (e: Exception) {
            Log.w(TAG, "requestRebind failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (ListenerHealth.owner === this) {
            ListenerHealth.connected = false
            ListenerHealth.owner = null
        }
        // cancel coroutine ทั้งหมดที่ยังทำงานอยู่ ป้องกัน scope ค้าง
        serviceScope.cancel()
        Log.d(TAG, "Service destroyed — scope cancelled")
    }

    // =========================================================================
    // Main entry point
    // =========================================================================

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            handleNotification(sbn)
        } catch (e: Exception) {
            // ห้ามให้ exception หลุดออกไป — crash ซ้ำๆ ทำให้ระบบเลิก bind listener
            Log.e(TAG, "onNotificationPosted error: ${e.message}", e)
        }
    }

    private fun handleNotification(sbn: StatusBarNotification?) {
        sbn ?: return

        val pkg = sbn.packageName ?: return

        val filterPackages = AppState.getFilterPackages(applicationContext)
        if (pkg !in filterPackages) return

        val appName = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        } catch (e: Exception) { "-" }

        val extras = sbn.notification?.extras

        fun extra(key: String): String =
            extras?.getCharSequence(key)?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() } ?: "-"

        val title   = extra("android.title")
        val text    = extra("android.text")
        val bigText = extra("android.bigText")
        val subText = extra("android.subText")

        val log = NotificationLog(
            timeMs      = sbn.postTime,
            packageName = pkg,
            appName     = appName,
            title       = title,
            text        = text,
            bigText     = bigText,
            subText     = subText,
        )
        AppState.addLog(log)
        broadcast()

        val bankLabel = BANK_PACKAGES[pkg] ?: "bank"

        val fullText = listOf(title, bigText.takeIf { it != "-" } ?: text)
            .filter { it != "-" }
            .joinToString(" ")

        val amount = AmountParser.parse(fullText)

        if (amount == null) {
            Log.d(TAG, "[$bankLabel] ไม่พบยอดเงินใน: $fullText")
            AppState.updateLastLog("[$bankLabel] อ่านได้แต่ parse ยอดไม่ได้: $fullText")
            broadcast()
            return
        }

        Log.i(TAG, "[$bankLabel] พบยอด ${AmountParser.format(amount)} — ส่ง API...")
        AppState.updateLastLog("${AmountParser.format(amount)} [$bankLabel] กำลังจับคู่...")
        broadcast()

        val notifId = "${pkg}_${sbn.postTime}_${amount}"

        serviceScope.launch {
            val result = ApiClient.confirmPayment(
                amount         = amount,
                bank           = bankLabel,
                timestampSec   = sbn.postTime / 1000L,
                notificationId = notifId,
                isTest         = false,
            )

            val resultText = when (result) {
                is ApiClient.ConfirmResult.Matched ->
                    "จับคู่สำเร็จ Order: ${result.orderId}"
                is ApiClient.ConfirmResult.AwaitingReview ->
                    "Match แล้ว รอ admin approve: ${result.orderId} (${result.reason})"
                is ApiClient.ConfirmResult.NoMatch ->
                    "ไม่พบ order ที่ตรง (${AmountParser.format(amount)})"
                is ApiClient.ConfirmResult.Duplicate ->
                    "Notification ซ้ำ — ข้ามแล้ว"
                is ApiClient.ConfirmResult.TestReceived ->
                    "Test received"
                is ApiClient.ConfirmResult.Failure ->
                    "Error: ${result.message}"
            }

            Log.i(TAG, "match result: $resultText")
            AppState.updateLastLog("${AmountParser.format(amount)} [$bankLabel]: $resultText")
            broadcast()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) { /* ไม่จำเป็น */ }

    private fun broadcast() {
        LocalBroadcastManager.getInstance(applicationContext)
            .sendBroadcast(Intent(AppState.ACTION_NOTIFICATION_RECEIVED))
    }
}
