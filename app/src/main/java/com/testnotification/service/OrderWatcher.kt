package com.testnotification.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.testnotification.MainActivity
import com.testnotification.R
import com.testnotification.data.PendingOrder
import com.testnotification.network.ApiClient
import com.testnotification.util.AmountParser
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * OrderWatcher — งานพื้นหลังที่ทำงานเมื่อ "มีเน็ต" ไม่ว่าจอเปิดหรือปิด
 *
 * tick() ถูกเรียกจาก: alarm (ทุก ~1 นาที), เน็ตกลับมา, จอเปิด/ปิด, listener เพิ่ง connected
 * แต่ละครั้งทำ 2 อย่าง (ข้ามทั้งหมดถ้าไม่มีเน็ต):
 *  1) ดึงออเดอร์ที่รอชำระ -> ถ้ามีออเดอร์ใหม่ที่ยังไม่เคยแจ้ง ส่งแจ้งเตือนเด้งบนเครื่อง
 *  2) อ่านแจ้งเตือนของแอพที่เลือกที่ค้างอยู่ในแถบ แล้วส่งจับคู่ (เหมือนปุ่ม แต่เฉพาะอันที่ยังไม่เคยส่ง/ส่งพลาด)
 *
 * ถือ WakeLock สั้นๆ (timeout) ระหว่างทำงานเท่านั้น
 */
object OrderWatcher {

    private const val TAG = "OrderWatcher"

    private const val CH_ORDER  = "order_new"
    private const val CH_RESULT = "pay_result"

    private const val PREFS        = "order_watch"
    private const val KEY_SEEN     = "seen_ids"
    private const val KEY_INIT     = "initialized"

    // กัน tick ถี่เกิน (หลายตัวกระตุ้นพร้อมกัน เช่น เน็ตกลับมา + จอเปิด)
    private const val MIN_TICK_GAP_MS = 15_000L
    private const val WAKE_TAG        = "com.testnotification:orderwatch"
    private const val WAKE_TIMEOUT_MS = 90_000L

    // เกิน 3 ออเดอร์ใหม่พร้อมกัน -> รวมเป็นแจ้งเตือนเดียว กันรัวเกินไป
    private const val MAX_INDIVIDUAL = 3

    @Volatile private var lastTickMs = -MIN_TICK_GAP_MS
    @Volatile private var running = false

    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "error: ${e.message}", e) }
    )

    // =========================================================================
    // Entry point
    // =========================================================================

    fun tick(ctx: Context, reason: String) {
        val app = ctx.applicationContext
        try {
            if (!hasInternet(app)) return
            val now = SystemClock.elapsedRealtime()
            if (running || now - lastTickMs < MIN_TICK_GAP_MS) return
            lastTickMs = now
            running = true

            val wl = try {
                app.getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)
                    .apply { setReferenceCounted(false); acquire(WAKE_TIMEOUT_MS) }
            } catch (e: Exception) { null }

            scope.launch {
                try {
                    Log.d(TAG, "tick ($reason)")
                    pollOrders(app)
                    TestNotificationService.instance?.scanActiveNotifications(auto = true)
                } finally {
                    running = false
                    try { if (wl?.isHeld == true) wl.release() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            running = false
            Log.w(TAG, "tick error: ${e.message}")
        }
    }

    fun hasInternet(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (e: Exception) { false }

    // =========================================================================
    // ออเดอร์ใหม่
    // =========================================================================

    private fun pollOrders(app: Context) {
        val res = ApiClient.fetchPendingOrders()
        if (res !is ApiClient.PendingOrdersResult.Success) return
        val orders = res.orders

        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val firstRun = !prefs.getBoolean(KEY_INIT, false)
        val seen = HashSet(prefs.getStringSet(KEY_SEEN, emptySet()) ?: emptySet())

        // ครั้งแรกแค่จดออเดอร์ที่มีอยู่ ไม่แจ้งรัว
        val fresh = if (firstRun) emptyList() else orders.filter { it.id !in seen }

        prefs.edit()
            .putBoolean(KEY_INIT, true)
            .putStringSet(KEY_SEEN, orders.map { it.id }.toSet())   // เก็บเฉพาะที่ยังรอชำระ ไม่โตไม่จำกัด
            .apply()

        if (fresh.isNotEmpty()) notifyNewOrders(app, fresh)
    }

    private fun notifyNewOrders(app: Context, fresh: List<PendingOrder>) {
        if (fresh.size > MAX_INDIVIDUAL) {
            post(app, CH_ORDER, 7001, "ออเดอร์ใหม่ ${fresh.size} รายการ", "รอชำระเงิน — แตะเพื่อเปิดดู")
            return
        }
        for (o in fresh) {
            val pay = if (o.uniqueAmount > 0) o.uniqueAmount else o.amount
            val label = o.productLabel.ifBlank { "ออเดอร์ใหม่" }
            post(app, CH_ORDER, 7100 + (o.id.hashCode() and 0xFFF), "ออเดอร์ใหม่ รอชำระ",
                "$label ยอด ${AmountParser.format(pay)}")
        }
    }

    // =========================================================================
    // ผลจับคู่ (เรียกจาก TestNotificationService)
    // =========================================================================

    /** แจ้งเตือนผลจับคู่ — เฉพาะผลที่ผู้ดูแลควรรู้ (ไม่แจ้ง ซ้ำ/ทดสอบ/error เครือข่าย กันรบกวน) */
    fun notifyResult(app: Context, result: ApiClient.ConfirmResult, amount: Double, bank: String) {
        val amt = AmountParser.format(amount)
        when (result) {
            is ApiClient.ConfirmResult.Matched ->
                post(app, CH_RESULT, 7200 + (result.orderId.hashCode() and 0xFFF),
                    "จับคู่ออเดอร์สำเร็จ", "$amt [$bank] Order ${result.orderId}")
            is ApiClient.ConfirmResult.AwaitingReview ->
                post(app, CH_RESULT, 7200 + (result.orderId.hashCode() and 0xFFF),
                    "ออเดอร์รอแอดมินตรวจสอบ", "$amt [$bank] Order ${result.orderId}")
            is ApiClient.ConfirmResult.NoMatch ->
                post(app, CH_RESULT, 7300 + ((amount * 100).toLong().hashCode() and 0xFFF),
                    "เงินเข้า แต่ไม่พบออเดอร์ที่ตรง", "$amt [$bank]")
            else -> Unit
        }
    }

    // =========================================================================
    // Notification helper
    // =========================================================================

    private fun post(app: Context, channel: String, id: Int, title: String, text: String) {
        try {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return

            ensureChannels(app)
            val open = PendingIntent.getActivity(
                app, 0, Intent(app, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(app, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .build()
            NotificationManagerCompat.from(app).notify(id, n)
        } catch (e: Exception) {
            Log.w(TAG, "post failed: ${e.message}")
        }
    }

    private fun ensureChannels(app: Context) {
        val nm = app.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH_ORDER) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_ORDER, "ออเดอร์ใหม่", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "แจ้งเมื่อมีออเดอร์ใหม่รอชำระเงิน" }
            )
        }
        if (nm.getNotificationChannel(CH_RESULT) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_RESULT, "ผลจับคู่การชำระเงิน", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "แจ้งผลจับคู่เงินเข้ากับออเดอร์" }
            )
        }
    }
}
