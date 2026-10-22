package com.testnotification

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.net.Uri
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.testnotification.data.AppState
import com.testnotification.data.NotificationLog
import com.testnotification.data.PendingOrder
import com.testnotification.databinding.ActivityMainBinding
import com.testnotification.network.ApiClient
import com.testnotification.service.ListenerHealth
import com.testnotification.service.TestNotificationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var logAdapter: LogAdapter
    private lateinit var orderAdapter: OrderAdapter

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
    private val amtFmt  = DecimalFormat("#,##0.00")
    private val uiScope = CoroutineScope(Dispatchers.Main)

    private val handler = Handler(Looper.getMainLooper())

    // poll orders ทุก 10 วินาที
    private val pollOrders = object : Runnable {
        override fun run() {
            loadPendingOrders()
            refreshAccessWarning()
            handler.postDelayed(this, 10_000L)
        }
    }

    // อัปเดตเวลาแบบ real-time ทุก 1 วินาที
    private val clockTick = object : Runnable {
        override fun run() {
            binding.tvLastRefresh.text = "อัปเดต ${timeFmt.format(Date())}"
            handler.postDelayed(this, 1_000L)
        }
    }

    private val notifReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refreshLog() }
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupButtons()
        if (savedInstanceState == null) requestNotifPermissionIfNeeded()
        startKeepAlive()
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            notifReceiver, IntentFilter(AppState.ACTION_NOTIFICATION_RECEIVED)
        )
        refreshAccessWarning()
        // เปิดแอพ = โอกาสซ่อม listener ที่หลุด bind (ไม่ทำอะไรถ้า listener ปกติ)
        ListenerHealth.ensureBound(applicationContext, "MainActivity.onResume")
        refreshLog()
        handler.post(pollOrders)
        handler.post(clockTick)  // เริ่ม real-time clock
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(pollOrders)
        handler.removeCallbacks(clockTick)  // หยุด clock เมื่อออกจากหน้า
        LocalBroadcastManager.getInstance(this).unregisterReceiver(notifReceiver)
    }

    // =========================================================================
    // Setup
    // =========================================================================

    private fun setupRecyclerView() {
        logAdapter = LogAdapter(timeFmt)
        binding.rvLog.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = logAdapter
            addItemDecoration(lineDivider())
            isNestedScrollingEnabled = false
        }

        orderAdapter = OrderAdapter(amtFmt, dateFmt)
        binding.rvOrders.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = orderAdapter
            addItemDecoration(lineDivider())
            isNestedScrollingEnabled = false
        }
    }

    /** เส้นคั่นรายการสีเดียวกับเส้นในหน้าแดชบอร์ดของเว็บ */
    private fun lineDivider(): DividerItemDecoration =
        DividerItemDecoration(this, DividerItemDecoration.VERTICAL).apply {
            ContextCompat.getDrawable(this@MainActivity, R.drawable.divider_line)?.let { setDrawable(it) }
        }

    private fun setupButtons() {
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnOpenSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        binding.btnScanNow.setOnClickListener { runManualScan() }
    }

    // =========================================================================
    // อ่านแจ้งเตือนที่ค้างอยู่ในแถบแจ้งเตือนตอนนี้ แล้วส่งไปจับคู่ (กดเองเมื่อแอพจับคู่ไม่ได้)
    // =========================================================================

    private val scanLabel = "อ่านแจ้งเตือนที่มีอยู่ตอนนี้"

    private fun runManualScan() {
        if (!isNotificationAccessEnabled()) {
            showScanStatus("ยังไม่ได้อนุญาต Notification Access — กดปุ่มเปิดสิทธิ์ด้านบนก่อน", PillKind.BAD)
            return
        }
        val service = TestNotificationService.instance
        if (service == null) {
            // listener ยังไม่เชื่อมต่อ — สั่งซ่อมให้ แล้วให้ผู้ใช้กดใหม่
            ListenerHealth.ensureBound(applicationContext, "manual-scan")
            showScanStatus("ตัวอ่านแจ้งเตือนยังไม่เชื่อมต่อ กำลังซ่อมให้ รอสักครู่แล้วกดอีกครั้ง", PillKind.WARN)
            return
        }

        binding.btnScanNow.isEnabled = false
        binding.btnScanNow.text = "กำลังอ่าน..."

        uiScope.launch {
            val r = service.scanActiveNotifications()
            if (isFinishing || isDestroyed) return@launch

            binding.btnScanNow.isEnabled = true
            binding.btnScanNow.text = scanLabel
            refreshLog()

            val maxMin = TestNotificationService.SCAN_MAX_AGE_MS / 60_000L
            when {
                r.error != null ->
                    showScanStatus("อ่านไม่สำเร็จ: ${r.error}", PillKind.BAD)
                r.found == 0 ->
                    showScanStatus("ไม่พบแจ้งเตือนของแอพที่เลือกไว้ในแถบแจ้งเตือนตอนนี้", PillKind.NEUTRAL)
                else -> {
                    val parts = mutableListOf("พบ ${r.found} แจ้งเตือน")
                    if (r.sent > 0)        parts += "ส่งจับคู่ ${r.sent} (สำเร็จ ${r.matched})"
                    if (r.alreadyDone > 0) parts += "จับคู่แล้ว ${r.alreadyDone}"
                    if (r.tooOld > 0)      parts += "เก่ากว่า $maxMin นาที ${r.tooOld} (ข้าม)"
                    if (r.noAmount > 0)    parts += "อ่านยอดไม่ได้ ${r.noAmount}"
                    val kind = when {
                        r.matched > 0 || (r.sent == 0 && r.alreadyDone > 0) -> PillKind.OK
                        r.sent > 0 || r.noAmount > 0                        -> PillKind.WARN
                        else                                                -> PillKind.NEUTRAL
                    }
                    showScanStatus(parts.joinToString(" · "), kind)
                }
            }
        }
    }

    private fun showScanStatus(message: String, kind: PillKind) {
        binding.tvScanStatus.text = "${timeFmt.format(Date())}  $message"
        binding.tvScanStatus.setTextColor(ContextCompat.getColor(this, kind.colorRes()))
        binding.tvScanStatus.visibility = View.VISIBLE
    }

    // =========================================================================
    // Access Warning — แสดงเฉพาะตอน OFF
    // =========================================================================

    private fun refreshAccessWarning() {
        val enabled = isNotificationAccessEnabled()
        binding.cardAccessWarning.visibility = if (enabled) View.GONE else View.VISIBLE

        // การ์ดสถิติ: สถานะการรับแจ้งเตือน
        val pill = binding.tvStatListenerPill
        when {
            !enabled                  -> pill.setPill("ปิดอยู่", PillKind.BAD)
            ListenerHealth.connected  -> pill.setPill("กำลังรับแจ้งเตือน", PillKind.OK)
            else                      -> pill.setPill("กำลังเชื่อมต่อ", PillKind.WARN)
        }
    }

    private fun isNotificationAccessEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return flat.contains(packageName)
    }

    // =========================================================================
    // Log
    // =========================================================================

    private fun refreshLog() {
        val logs = AppState.getLogs()
        logAdapter.submitList(logs)
        binding.tvLogCount.text = "${logs.size} รายการ"
        binding.tvLogCount.visibility = if (logs.isEmpty()) View.GONE else View.VISIBLE
        binding.tvStatLogs.text = logs.size.toString()
        binding.tvLogEmpty.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
        binding.rvLog.visibility      = if (logs.isEmpty()) View.GONE   else View.VISIBLE
    }

    // =========================================================================
    // Orders
    // =========================================================================

    private fun loadPendingOrders() {
        uiScope.launch {
            val result = withContext(Dispatchers.IO) { ApiClient.fetchPendingOrders() }
            if (result is ApiClient.PendingOrdersResult.Success) {
                val orders = result.orders
                orderAdapter.submitList(orders)
                binding.tvOrderCount.text  = "${orders.size} รายการ"
                binding.tvOrderCount.visibility = if (orders.isEmpty()) View.GONE else View.VISIBLE
                binding.tvStatOrders.text = orders.size.toString()
                if (orders.isEmpty()) binding.tvStatOrdersPill.setPill("เคลียร์แล้ว", PillKind.OK)
                else                  binding.tvStatOrdersPill.setPill("รอยอดโอน", PillKind.WARN)
                binding.tvOrdersEmpty.visibility = if (orders.isEmpty()) View.VISIBLE else View.GONE
                binding.rvOrders.visibility      = if (orders.isEmpty()) View.GONE   else View.VISIBLE
            } else {
                binding.tvStatOrdersPill.setPill("โหลดไม่สำเร็จ", PillKind.BAD)
            }
        }
    }

    // =========================================================================
    // Permission + Service
    // =========================================================================

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { requestBatteryExemptionIfNeeded() }   // ผลไม่สำคัญ — WarningCard จัดการให้

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestBatteryExemptionIfNeeded()
    }

    /**
     * ขอยกเว้น Battery Optimization — ลดโอกาสที่ระบบ/ผู้ผลิต (Samsung/Xiaomi ฯลฯ) ฆ่าโปรเซส
     * จนทำให้ NotificationListener หลุด และทำให้ KeepAlive start จากเบื้องหลังได้บน Android 12+
     */
    private fun requestBatteryExemptionIfNeeded() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (pm.isIgnoringBatteryOptimizations(packageName)) return
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            // เครื่องบางรุ่นไม่มีหน้านี้ — ข้ามได้
        }
    }

    private fun startKeepAlive() {
        val intent = Intent(this, com.testnotification.service.KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }
}

// =============================================================================
// LogAdapter
// =============================================================================

class LogAdapter(private val timeFmt: SimpleDateFormat)
    : RecyclerView.Adapter<LogAdapter.VH>() {

    private var items: List<NotificationLog> = emptyList()

    fun submitList(list: List<NotificationLog>) { items = list; notifyDataSetChanged() }
    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_notification_log, parent, false))
    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvTime        = view.findViewById<TextView>(R.id.tvTime)
        private val tvApp         = view.findViewById<TextView>(R.id.tvApp)
        private val tvTitle       = view.findViewById<TextView>(R.id.tvTitle)
        private val tvText        = view.findViewById<TextView>(R.id.tvText)
        private val tvMatchResult = view.findViewById<TextView>(R.id.tvMatchResult)
        // hidden views — ยังต้องมีเพื่อ inflate ไม่ crash
        private val tvPackage = view.findViewById<TextView>(R.id.tvPackage)
        private val tvBig     = view.findViewById<TextView>(R.id.tvBig)
        private val tvSub     = view.findViewById<TextView>(R.id.tvSub)

        fun bind(log: NotificationLog) {
            tvTime.text    = timeFmt.format(Date(log.timeMs))
            tvApp.text     = log.appName
            tvTitle.text   = log.title.takeIf { it != "-" } ?: ""
            tvText.text    = log.text.takeIf  { it != "-" } ?: ""
            tvPackage.text = log.packageName
            tvBig.text     = ""
            tvSub.text     = ""
            if (log.matchResult != null) {
                val kind = resultKind(log.matchResult)
                tvMatchResult.text = log.matchResult
                tvMatchResult.setBackgroundResource(when (kind) {
                    PillKind.OK      -> R.drawable.bg_result_ok
                    PillKind.WARN    -> R.drawable.bg_result_warn
                    PillKind.BAD     -> R.drawable.bg_result_bad
                    PillKind.NEUTRAL -> R.drawable.bg_result_neutral
                })
                tvMatchResult.setTextColor(ContextCompat.getColor(tvMatchResult.context, kind.colorRes()))
                tvMatchResult.visibility = View.VISIBLE
            } else {
                tvMatchResult.visibility = View.GONE
            }
        }
    }
}

// =============================================================================
// OrderAdapter
// =============================================================================

class OrderAdapter(
    private val amtFmt: DecimalFormat,
    private val dateFmt: SimpleDateFormat,
) : RecyclerView.Adapter<OrderAdapter.VH>() {

    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
    private var items: List<PendingOrder> = emptyList()

    fun submitList(list: List<PendingOrder>) { items = list; notifyDataSetChanged() }
    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_pending_order, parent, false))
    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvOrderId = view.findViewById<TextView>(R.id.tvOrderId)
        private val tvProduct = view.findViewById<TextView>(R.id.tvProduct)
        private val tvAmount  = view.findViewById<TextView>(R.id.tvAmount)
        private val tvEmail   = view.findViewById<TextView>(R.id.tvEmail)
        private val tvTime    = view.findViewById<TextView>(R.id.tvTime)

        fun bind(o: PendingOrder) {
            tvOrderId.text = "#${o.id.take(6).uppercase()}"
            tvProduct.text = o.productLabel
            tvAmount.text  = "฿${amtFmt.format(o.uniqueAmount)}"
            tvEmail.text   = o.userEmail
            val date = try { isoFmt.parse(o.createdAt.take(19)) } catch (e: Exception) { null }
            tvTime.text    = date?.let { dateFmt.format(it) } ?: o.createdAt.take(16)
        }
    }
}

// =============================================================================
// Pill helpers — สีสถานะเดียวกับพิลล์ในหน้าแดชบอร์ดของเว็บ
// =============================================================================

internal enum class PillKind { OK, WARN, BAD, NEUTRAL }

internal fun PillKind.colorRes(): Int = when (this) {
    PillKind.OK      -> R.color.status_on
    PillKind.WARN    -> R.color.gold_deep
    PillKind.BAD     -> R.color.status_off
    PillKind.NEUTRAL -> R.color.text_secondary
}

internal fun TextView.setPill(label: String, kind: PillKind) {
    text = label
    setBackgroundResource(when (kind) {
        PillKind.OK      -> R.drawable.bg_pill_ok
        PillKind.WARN    -> R.drawable.bg_pill_warn
        PillKind.BAD     -> R.drawable.bg_pill_bad
        PillKind.NEUTRAL -> R.drawable.bg_pill_neutral
    })
    setTextColor(ContextCompat.getColor(context, kind.colorRes()))
}

/** เลือกสีพิลล์จากข้อความผลการจับคู่ที่ TestNotificationService สร้าง */
internal fun resultKind(text: String): PillKind = when {
    "จับคู่สำเร็จ" in text      -> PillKind.OK
    "รอ admin approve" in text -> PillKind.WARN
    "parse ยอดไม่ได้" in text   -> PillKind.WARN
    "ไม่พบ order" in text      -> PillKind.BAD
    "Error:" in text           -> PillKind.BAD
    else                       -> PillKind.NEUTRAL
}
