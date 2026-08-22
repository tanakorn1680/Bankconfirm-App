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
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
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
    private lateinit var adapter: LogAdapter
    private lateinit var orderAdapter: OrderAdapter

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
    private val amtFmt  = DecimalFormat("#,##0.00")
    private val uiScope = CoroutineScope(Dispatchers.Main)

    // poll orders ทุก 10 วินาที
    private val handler = Handler(Looper.getMainLooper())
    private val pollOrders = object : Runnable {
        override fun run() { loadPendingOrders(); handler.postDelayed(this, 10_000L) }
    }

    // ── LocalBroadcast Receiver (เดิม ไม่แตะ) ─────────────────────────
    private val notifReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshLog()
        }
    }

    // =========================================================================
    // Lifecycle (เดิม ไม่แตะ — เพิ่มแค่ Orders)
    // =========================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupButtons()
        showTab(Tab.DEBUG)

        // ขอ POST_NOTIFICATIONS permission (Android 13+)
        requestNotifPermissionIfNeeded()

        // start KeepAliveService
        val keepAlive = Intent(this, com.testnotification.service.KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(keepAlive)
        } else {
            startService(keepAlive)
        }
    }

    // ขอ POST_NOTIFICATIONS permission — Android 13+ บังคับขอ runtime
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Toast.makeText(this, "✓ อนุญาตการแจ้งเตือนแล้ว", Toast.LENGTH_SHORT).show()
        } else {
            // ถ้าปฏิเสธ → พาไปเปิดเองใน Settings
            Toast.makeText(this, "กรุณาเปิดการแจ้งเตือนใน Settings เพื่อให้แอพทำงานพื้นหลังได้", Toast.LENGTH_LONG).show()
            try {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                startActivity(intent)
            } catch (e: Exception) { /* ignore */ }
        }
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED) return
        notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            notifReceiver,
            IntentFilter(AppState.ACTION_NOTIFICATION_RECEIVED)
        )
        refreshAccessStatus()
        refreshLog()
        handler.post(pollOrders)   // เริ่ม poll orders
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(pollOrders)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(notifReceiver)
    }

    // =========================================================================
    // Setup
    // =========================================================================

    private fun setupRecyclerView() {
        // Log adapter (เดิม ไม่แตะ)
        adapter = LogAdapter(timeFmt)
        binding.rvLog.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter       = this@MainActivity.adapter
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
        }

        // Order adapter (ใหม่)
        orderAdapter = OrderAdapter(amtFmt, dateFmt)
        binding.rvOrders.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter       = orderAdapter
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
        }
    }

    private fun setupButtons() {
        // เดิม
        binding.btnOpenSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        // ใหม่ — tab switch
        binding.btnTabOrders.setOnClickListener { showTab(Tab.ORDERS) }
        binding.btnTabDebug.setOnClickListener  { showTab(Tab.DEBUG) }

        // ใหม่ — refresh orders
        binding.btnRefreshOrders.setOnClickListener {
            binding.btnRefreshOrders.isEnabled = false
            loadPendingOrders(manual = true)
        }
    }

    // =========================================================================
    // Tab switching (ใหม่)
    // =========================================================================

    private enum class Tab { ORDERS, DEBUG }

    private fun showTab(tab: Tab) {
        val isOrders = (tab == Tab.ORDERS)
        binding.layoutOrders.visibility = if (isOrders) View.VISIBLE else View.GONE
        binding.layoutDebug.visibility  = if (isOrders) View.GONE   else View.VISIBLE
        binding.btnTabOrders.setTextColor(getColor(if (isOrders) R.color.tab_active else R.color.tab_inactive))
        binding.btnTabDebug.setTextColor(getColor(if (isOrders) R.color.tab_inactive else R.color.tab_active))
    }

    // =========================================================================
    // Orders (ใหม่ — ยกจาก BankApp เป๊ะ)
    // =========================================================================

    private fun loadPendingOrders(manual: Boolean = false) {
        uiScope.launch {
            val result = withContext(Dispatchers.IO) { ApiClient.fetchPendingOrders() }
            binding.btnRefreshOrders.isEnabled = true
            when (result) {
                is ApiClient.PendingOrdersResult.Success -> {
                    val orders = result.orders
                    orderAdapter.submitList(orders)
                    binding.tvOrdersEmpty.visibility = if (orders.isEmpty()) View.VISIBLE else View.GONE
                    binding.rvOrders.visibility      = if (orders.isEmpty()) View.GONE   else View.VISIBLE
                    binding.tvOrderCount.text        = "${orders.size} รายการรอจับคู่"
                    binding.tvLastRefresh.text       = "อัปเดต ${timeFmt.format(Date())}"
                    if (manual) Toast.makeText(this@MainActivity, "รีเฟรชแล้ว", Toast.LENGTH_SHORT).show()
                }
                is ApiClient.PendingOrdersResult.Error -> {
                    if (manual) Toast.makeText(this@MainActivity, "เชื่อมต่อไม่ได้: ${result.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // =========================================================================
    // Refresh (เดิม ไม่แตะ)
    // =========================================================================

    private fun refreshAccessStatus() {
        val enabled = isNotificationAccessEnabled()
        binding.tvAccessStatus.text = if (enabled)
            "Notification Access: ON ✓"
        else
            "Notification Access: OFF"
        binding.tvAccessStatus.setTextColor(
            getColor(if (enabled) R.color.status_on else R.color.status_off)
        )
    }

    private fun refreshLog() {
        val logs = AppState.getLogs()
        adapter.submitList(logs)
        if (logs.isEmpty()) {
            binding.tvLogEmpty.visibility = View.VISIBLE
            binding.rvLog.visibility = View.GONE
        } else {
            binding.tvLogEmpty.visibility = View.GONE
            binding.rvLog.visibility = View.VISIBLE
        }
    }

    private fun isNotificationAccessEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return flat.contains(packageName)
    }
}

// =============================================================================
// LogAdapter (เดิม ไม่แตะ)
// =============================================================================

class LogAdapter(
    private val timeFmt: SimpleDateFormat
) : RecyclerView.Adapter<LogAdapter.VH>() {

    private var items: List<NotificationLog> = emptyList()

    fun submitList(list: List<NotificationLog>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notification_log, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvTime        = view.findViewById<TextView>(R.id.tvTime)
        private val tvPackage     = view.findViewById<TextView>(R.id.tvPackage)
        private val tvApp         = view.findViewById<TextView>(R.id.tvApp)
        private val tvTitle       = view.findViewById<TextView>(R.id.tvTitle)
        private val tvText        = view.findViewById<TextView>(R.id.tvText)
        private val tvBig         = view.findViewById<TextView>(R.id.tvBig)
        private val tvSub         = view.findViewById<TextView>(R.id.tvSub)
        private val tvMatchResult = view.findViewById<TextView>(R.id.tvMatchResult)

        fun bind(log: NotificationLog) {
            tvTime.text    = timeFmt.format(Date(log.timeMs))
            tvPackage.text = log.packageName
            tvApp.text     = log.appName
            tvTitle.text   = log.title
            tvText.text    = log.text
            tvBig.text     = if (log.bigText == "-") "" else log.bigText
            tvSub.text     = if (log.subText == "-") "" else log.subText
            itemView.findViewById<View>(R.id.rowBig).visibility =
                if (log.bigText == "-") View.GONE else View.VISIBLE
            itemView.findViewById<View>(R.id.rowSub).visibility =
                if (log.subText == "-") View.GONE else View.VISIBLE

            // Match result — แสดงเฉพาะ notification ธนาคาร
            if (log.matchResult != null) {
                tvMatchResult.text       = log.matchResult
                tvMatchResult.visibility = View.VISIBLE
            } else {
                tvMatchResult.visibility = View.GONE
            }
        }
    }
}

// =============================================================================
// OrderAdapter (ใหม่ — ยกจาก BankApp เป๊ะ)
// =============================================================================

class OrderAdapter(
    private val amtFmt: DecimalFormat,
    private val dateFmt: SimpleDateFormat,
) : RecyclerView.Adapter<OrderAdapter.VH>() {

    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
    private var items: List<PendingOrder> = emptyList()

    fun submitList(list: List<PendingOrder>) { items = list; notifyDataSetChanged() }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_pending_order, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvProduct = view.findViewById<TextView>(R.id.tvProduct)
        private val tvAmount  = view.findViewById<TextView>(R.id.tvAmount)
        private val tvEmail   = view.findViewById<TextView>(R.id.tvEmail)
        private val tvTime    = view.findViewById<TextView>(R.id.tvTime)

        fun bind(o: PendingOrder) {
            tvProduct.text = o.productLabel
            tvAmount.text  = "฿${amtFmt.format(o.uniqueAmount)}"
            tvEmail.text   = o.userEmail
            val date = try { isoFmt.parse(o.createdAt.take(19)) } catch (e: Exception) { null }
            tvTime.text    = date?.let { dateFmt.format(it) } ?: o.createdAt.take(16)
        }
    }
}
