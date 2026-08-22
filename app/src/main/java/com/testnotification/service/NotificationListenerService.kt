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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * TestNotificationService
 *
 * Flow:
 *   onNotificationPosted()
 *     → บันทึก raw log → broadcast → MainActivity อัปเดท Debug tab (เดิม ไม่เปลี่ยน)
 *     → ถ้าเป็น KBank / TrueMoney:
 *         → parse ยอดเงิน (AmountParser)
 *         → ถ้าเจอยอด → POST /api/bank-amount (confirmPayment)
 *         → บันทึก match result กลับใน log row เดิม
 */
class TestNotificationService : NotificationListenerService() {

    companion object {
        private const val TAG = "TestNotifService"

        // Package ที่จะ match กับ order (กรองเฉพาะธนาคาร)
        private val BANK_PACKAGES = mapOf(
            "com.kasikorn.retail.mbanking.wap" to "kbank",
            "th.co.truemoney.wallet"            to "truemoney",
            "com.truemoney.android.wallet"      to "truemoney",
        )

        // SHOW_ALL = true  → แสดง log ทุกแอป (debug)
        // SHOW_ALL = false → แสดงเฉพาะ BANK_PACKAGES
        const val SHOW_ALL = true
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created — SHOW_ALL=$SHOW_ALL")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "Listener disconnected — requesting rebind")
        // reconnect อัตโนมัติ — สำคัญมากสำหรับ MIUI ที่ kill service บ่อย
        requestRebind(
            android.content.ComponentName(this, TestNotificationService::class.java)
        )
    }

    // =========================================================================
    // Main entry point
    // =========================================================================

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        val pkg = sbn.packageName ?: return

        // ── Package filter ─────────────────────────────────────────────────
        if (!SHOW_ALL && pkg !in BANK_PACKAGES) return

        // ── App name ──────────────────────────────────────────────────────
        val appName = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        } catch (e: Exception) { "-" }

        // ── Raw extras ────────────────────────────────────────────────────
        val extras = sbn.notification?.extras

        fun extra(key: String): String =
            extras?.getCharSequence(key)?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() } ?: "-"

        val title   = extra("android.title")
        val text    = extra("android.text")
        val bigText = extra("android.bigText")
        val subText = extra("android.subText")

        // ── บันทึก log (เดิม — ไม่เปลี่ยน) ───────────────────────────────
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

        // ── Match logic — เฉพาะ BANK_PACKAGES ─────────────────────────────
        val bankLabel = BANK_PACKAGES[pkg] ?: return  // ไม่ใช่ธนาคาร → จบ

        val fullText = listOf(title, bigText.takeIf { it != "-" } ?: text)
            .filter { it != "-" }
            .joinToString(" ")

        val amount = AmountParser.parse(fullText)

        if (amount == null) {
            Log.d(TAG, "[$bankLabel] ไม่พบยอดเงินใน: $fullText")
            AppState.updateLastLog("⚠️ [$bankLabel] อ่านได้แต่ parse ยอดไม่ได้: $fullText")
            broadcast()
            return
        }

        Log.i(TAG, "[$bankLabel] พบยอด ${AmountParser.format(amount)} — ส่ง API...")
        AppState.updateLastLog("⏳ ${AmountParser.format(amount)} [$bankLabel] กำลังจับคู่...")
        broadcast()

        // สร้าง notification_id unique จาก pkg + postTime + amount
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
                    "✅ จับคู่สำเร็จ! Order: ${result.orderId}"
                is ApiClient.ConfirmResult.AwaitingReview ->
                    "🔶 Match แล้ว รอ admin approve: ${result.orderId} (${result.reason})"
                is ApiClient.ConfirmResult.NoMatch ->
                    "❌ ไม่พบ order ที่ตรง (${AmountParser.format(amount)})"
                is ApiClient.ConfirmResult.Duplicate ->
                    "⏭️ Notification ซ้ำ — ข้ามแล้ว"
                is ApiClient.ConfirmResult.TestReceived ->
                    "🧪 Test received"
                is ApiClient.ConfirmResult.Failure ->
                    "💥 Error: ${result.message}"
            }

            Log.i(TAG, "match result: $resultText")
            AppState.updateLastLog("${AmountParser.format(amount)} [$bankLabel] → $resultText")
            broadcast()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) { /* ไม่จำเป็น */ }

    // =========================================================================
    // Helper
    // =========================================================================

    private fun broadcast() {
        LocalBroadcastManager.getInstance(applicationContext)
            .sendBroadcast(Intent(AppState.ACTION_NOTIFICATION_RECEIVED))
    }
}
