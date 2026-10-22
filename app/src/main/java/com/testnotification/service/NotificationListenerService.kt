package com.testnotification.service

import android.content.Intent
import android.os.PowerManager
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
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

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

        // ปุ่ม "อ่านแจ้งเตือนที่มีอยู่ตอนนี้": ส่งเฉพาะแจ้งเตือนที่โพสต์ไม่เกินช่วงนี้
        // (ออเดอร์ pending หมดอายุใน ~10 นาที — แจ้งเตือนเก่ากว่านี้ไม่ควรไปจับคู่กับออเดอร์ใหม่ที่ยอดซ้ำ)
        const val SCAN_MAX_AGE_MS = 30 * 60 * 1000L

        /** service ที่ connected อยู่ตอนนี้ (null = ยังไม่ได้ต่อ/หลุด) — ใช้กับปุ่มสแกนในหน้าหลัก */
        @Volatile var instance: TestNotificationService? = null
            private set

        // WakeLock สั้นๆ เฉพาะช่วงส่ง API ของแจ้งเตือนที่ตรงแอพที่เลือก (ไม่ถือค้างตลอดเวลา)
        // timeout > เวลารวม connect+read+write ของ ApiClient เผื่อเน็ตช้า
        private const val WORK_WAKE_TAG        = "com.testnotification:confirm"
        private const val WORK_WAKE_TIMEOUT_MS = 90_000L

        // scope ระดับโปรเซส — ไม่ถูก cancel ตอน service destroy จึงไม่ทำให้ request ที่กำลังส่งหาย
        private val workScope = CoroutineScope(
            Dispatchers.IO + SupervisorJob() +
                CoroutineExceptionHandler { _, e -> Log.e(TAG, "work error: ${e.message}", e) }
        )
    }

    /** ผลของการกดปุ่มอ่านแจ้งเตือนที่ค้างอยู่ */
    data class ScanResult(
        val found:       Int = 0,   // แจ้งเตือนของแอพที่เลือกไว้ที่ค้างอยู่ในแถบ
        val sent:        Int = 0,   // ส่งไปจับคู่รอบนี้
        val matched:     Int = 0,   // จับคู่สำเร็จ/รอแอดมินรอบนี้
        val alreadyDone: Int = 0,   // เคยจับคู่สำเร็จแล้ว ข้าม
        val tooOld:      Int = 0,   // เก่ากว่า SCAN_MAX_AGE_MS ข้าม
        val noAmount:    Int = 0,   // อ่านยอดเงินไม่ได้
        val error:       String? = null,
    )

    private val scanning = AtomicBoolean(false)

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
        instance = this
        Log.d(TAG, "Listener connected")
        OrderWatcher.tick(applicationContext, "listener-connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (ListenerHealth.owner === this) ListenerHealth.connected = false
        if (instance === this) instance = null
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
        if (instance === this) instance = null
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

    /** แจ้งเตือนใหม่ที่เข้ามาแบบปกติ */
    private fun handleNotification(sbn: StatusBarNotification?) {
        sbn ?: return

        val log = buildLog(sbn) ?: return
        AppState.addLog(log)
        broadcast()

        val bankLabel = bankLabelOf(log.packageName)
        val amount = AmountParser.parse(fullTextOf(log))

        if (amount == null) {
            Log.d(TAG, "[$bankLabel] ไม่พบยอดเงินใน: ${fullTextOf(log)}")
            AppState.updateLogResult(log.key, "[$bankLabel] อ่านได้แต่ parse ยอดไม่ได้: ${fullTextOf(log)}")
            broadcast()
            return
        }

        Log.i(TAG, "[$bankLabel] พบยอด ${AmountParser.format(amount)} — ส่ง API...")
        AppState.updateLogResult(log.key, "${AmountParser.format(amount)} [$bankLabel] กำลังจับคู่...")
        broadcast()

        // ปลุก CPU เฉพาะช่วงส่ง API แล้วปล่อยทันทีเมื่อเสร็จ (event-driven ไม่มี WakeLock ค้าง)
        val wl = acquireWorkWakeLock()
        workScope.launch {
            try {
                val result = ApiClient.confirmPayment(
                    amount         = amount,
                    bank           = bankLabel,
                    timestampSec   = sbn.postTime / 1000L,
                    notificationId = notificationIdOf(log.packageName, sbn.postTime, amount),
                    isTest         = false,
                )

                val resultText = describe(result, amount)
                Log.i(TAG, "match result: $resultText")
                AppState.updateLogResult(log.key, "${AmountParser.format(amount)} [$bankLabel]: $resultText")
                broadcast()
                OrderWatcher.notifyResult(applicationContext, result, amount, bankLabel)
            } finally {
                releaseWorkWakeLock(wl)
            }
        }
    }

    private fun acquireWorkWakeLock(): PowerManager.WakeLock? = try {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WORK_WAKE_TAG)
            .apply { setReferenceCounted(false); acquire(WORK_WAKE_TIMEOUT_MS) }
    } catch (e: Exception) {
        Log.w(TAG, "acquire wakelock failed: ${e.message}")
        null
    }

    private fun releaseWorkWakeLock(wl: PowerManager.WakeLock?) {
        try { if (wl?.isHeld == true) wl.release() } catch (e: Exception) {
            Log.w(TAG, "release wakelock failed: ${e.message}")
        }
    }

    // =========================================================================
    // ปุ่ม "อ่านแจ้งเตือนที่มีอยู่ตอนนี้" — อ่านแจ้งเตือนที่ค้างอยู่ในแถบแล้วส่งไปจับคู่
    //
    // ใช้ notification_id รูปแบบเดียวกับแจ้งเตือนใหม่ (pkg_postTime_amount) ฝั่งเซิร์ฟเวอร์
    // กันซ้ำด้วย UNIQUE จึงกดซ้ำได้ปลอดภัย ไม่ทำให้ตัดยอดซ้ำ
    // ช่วยได้เมื่อ: แอพพลาดแจ้งเตือน (listener หลุด), เน็ตล้มตอนส่ง, ผลค้าง "กำลังจับคู่"
    // ไม่ช่วยเมื่อ: เซิร์ฟเวอร์รับแจ้งเตือนนั้นไว้แล้วแต่ไม่พบออเดอร์ (ซ้ำ -> ไม่จับคู่ใหม่ ให้แอดมินตรวจเอง)
    // =========================================================================

    /**
     * @param auto true = เรียกอัตโนมัติจาก OrderWatcher (เน็ตกลับมา/จอเปิดปิด/alarm) — ส่งเฉพาะแจ้งเตือนที่ยังไม่เคยส่ง,
     *             ส่งพลาด (Error) หรือค้าง "กำลังจับคู่" เกิน 2 นาที ; false = ผู้ใช้กดปุ่มเอง (ส่งซ้ำได้ ปลอดภัยเพราะเซิร์ฟเวอร์กันซ้ำ)
     */
    suspend fun scanActiveNotifications(auto: Boolean = false): ScanResult {
        if (!scanning.compareAndSet(false, true)) return ScanResult(error = "กำลังอ่านอยู่")
        try {
            return withContext(Dispatchers.IO) { scanInternal(auto) }
        } catch (e: Exception) {
            Log.e(TAG, "scan error: ${e.message}", e)
            return ScanResult(error = e.message ?: "เกิดข้อผิดพลาด")
        } finally {
            scanning.set(false)
        }
    }

    private fun scanInternal(auto: Boolean): ScanResult {
        val active: Array<StatusBarNotification> = try {
            getActiveNotifications() ?: emptyArray()
        } catch (e: Exception) {
            Log.w(TAG, "getActiveNotifications failed: ${e.message}")
            return ScanResult(error = "อ่านแจ้งเตือนในแถบไม่ได้")
        }

        val now = System.currentTimeMillis()
        var found = 0; var sent = 0; var matched = 0
        var done = 0; var old = 0; var noAmount = 0

        // เรียงเก่า -> ใหม่ เพื่อให้อันใหม่สุดอยู่บนสุดของ log
        for (sbn in active.sortedBy { it.postTime }) {
            val log = buildLog(sbn) ?: continue          // ไม่ใช่แอพที่เลือกไว้
            found++

            if (now - sbn.postTime > SCAN_MAX_AGE_MS) { old++; continue }

            val prev = AppState.findLog(log.key)?.matchResult
            if (prev != null && isFinalResult(prev)) { done++; continue }

            if (auto && prev != null) {
                // โหมดอัตโนมัติ: ไม่ส่งซ้ำอันที่เซิร์ฟเวอร์ตอบแล้ว (เช่น ไม่พบ order/ซ้ำ) กันยิง API ทุกนาที
                val stuck = "กำลังจับคู่" in prev && now - sbn.postTime > 2 * 60 * 1000L
                if (!("Error:" in prev || stuck)) { done++; continue }
            }

            if (AppState.findLog(log.key) == null) AppState.addLog(log)

            val bankLabel = bankLabelOf(log.packageName)
            val amount = AmountParser.parse(fullTextOf(log))
            if (amount == null) {
                noAmount++
                AppState.updateLogResult(log.key, "[$bankLabel] อ่านได้แต่ parse ยอดไม่ได้: ${fullTextOf(log)}")
                broadcast()
                continue
            }

            AppState.updateLogResult(log.key, "${AmountParser.format(amount)} [$bankLabel] กำลังจับคู่...")
            broadcast()

            val result = ApiClient.confirmPayment(
                amount         = amount,
                bank           = bankLabel,
                timestampSec   = sbn.postTime / 1000L,
                notificationId = notificationIdOf(log.packageName, sbn.postTime, amount),
                isTest         = false,
            )
            sent++
            if (result is ApiClient.ConfirmResult.Matched ||
                result is ApiClient.ConfirmResult.AwaitingReview) matched++

            // ซ้ำ + เคยได้ผล "ไม่พบ order" แล้ว -> คงข้อความเดิมไว้ ไม่ทับด้วย "ซ้ำ"
            val text = if (result is ApiClient.ConfirmResult.Duplicate &&
                prev != null && "ไม่พบ order" in prev) prev
            else "${AmountParser.format(amount)} [$bankLabel]: ${describe(result, amount)}"

            AppState.updateLogResult(log.key, text)
            broadcast()
            OrderWatcher.notifyResult(applicationContext, result, amount, bankLabel)
        }

        Log.i(TAG, "scan: found=$found sent=$sent matched=$matched done=$done old=$old noAmount=$noAmount")
        return ScanResult(found, sent, matched, done, old, noAmount)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** สร้าง log จากแจ้งเตือน — null ถ้าไม่ใช่แอพที่เลือกไว้ในหน้าตั้งค่า */
    private fun buildLog(sbn: StatusBarNotification): NotificationLog? {
        val pkg = sbn.packageName ?: return null

        val filterPackages = AppState.getFilterPackages(applicationContext)
        if (pkg !in filterPackages) return null

        val appName = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        } catch (e: Exception) { "-" }

        val extras = sbn.notification?.extras

        fun extra(key: String): String =
            extras?.getCharSequence(key)?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() } ?: "-"

        return NotificationLog(
            timeMs      = sbn.postTime,
            packageName = pkg,
            appName     = appName,
            title       = extra("android.title"),
            text        = extra("android.text"),
            bigText     = extra("android.bigText"),
            subText     = extra("android.subText"),
            key         = "${sbn.key}|${sbn.postTime}",
        )
    }

    private fun bankLabelOf(pkg: String): String = BANK_PACKAGES[pkg] ?: "bank"

    private fun fullTextOf(log: NotificationLog): String =
        listOf(log.title, log.bigText.takeIf { it != "-" } ?: log.text)
            .filter { it != "-" }
            .joinToString(" ")

    private fun notificationIdOf(pkg: String, postTime: Long, amount: Double): String =
        "${pkg}_${postTime}_${amount}"

    private fun describe(result: ApiClient.ConfirmResult, amount: Double): String = when (result) {
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

    /** ผลที่ถือว่าเสร็จแล้ว ไม่ต้องส่งซ้ำตอนกดสแกน */
    private fun isFinalResult(text: String): Boolean =
        "จับคู่สำเร็จ" in text || "รอ admin approve" in text

    override fun onNotificationRemoved(sbn: StatusBarNotification?) { /* ไม่จำเป็น */ }

    private fun broadcast() {
        LocalBroadcastManager.getInstance(applicationContext)
            .sendBroadcast(Intent(AppState.ACTION_NOTIFICATION_RECEIVED))
    }
}
