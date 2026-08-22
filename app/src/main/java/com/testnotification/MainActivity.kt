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
    private lateinit var logAdapter: LogAdapter
    private lateinit var orderAdapter: OrderAdapter

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
    private val amtFmt  = DecimalFormat("#,##0.00")
    private val uiScope = CoroutineScope(Dispatchers.Main)

    private val handler = Handler(Looper.getMainLooper())
    private val pollOrders = object : Runnable {
        override fun run() { loadPendingOrders(); handler.postDelayed(this, 10_000L) }
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
        requestNotifPermissionIfNeeded()
        startKeepAlive()
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            notifReceiver, IntentFilter(AppState.ACTION_NOTIFICATION_RECEIVED)
        )
        refreshAccessWarning()
        refreshLog()
        handler.post(pollOrders)
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
        logAdapter = LogAdapter(timeFmt)
        binding.rvLog.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = logAdapter
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
            isNestedScrollingEnabled = false
        }

        orderAdapter = OrderAdapter(amtFmt, dateFmt)
        binding.rvOrders.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = orderAdapter
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
            isNestedScrollingEnabled = false
        }
    }

    private fun setupButtons() {
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnOpenSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    // =========================================================================
    // Access Warning — แสดงเฉพาะตอน OFF
    // =========================================================================

    private fun refreshAccessWarning() {
        val enabled = isNotificationAccessEnabled()
        binding.cardAccessWarning.visibility = if (enabled) View.GONE else View.VISIBLE
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
        binding.tvLogCount.text = if (logs.isEmpty()) "" else "${logs.size} รายการ"
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
                binding.tvOrderCount.text  = if (orders.isEmpty()) "" else "${orders.size} รายการ"
                binding.tvLastRefresh.text = "อัปเดต ${timeFmt.format(Date())}"
                binding.tvOrdersEmpty.visibility = if (orders.isEmpty()) View.VISIBLE else View.GONE
                binding.rvOrders.visibility      = if (orders.isEmpty()) View.GONE   else View.VISIBLE
            }
        }
    }

    // =========================================================================
    // Permission + Service
    // =========================================================================

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* ผลไม่สำคัญ — WarningCard จัดการให้ */ }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED) return
        notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
                tvMatchResult.text       = log.matchResult
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
