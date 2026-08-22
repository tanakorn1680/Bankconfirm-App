package com.testnotification

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.testnotification.data.AppState
import com.testnotification.databinding.ActivitySettingsBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var appAdapter: AppPickerAdapter
    private val uiScope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupButtons()
        loadInstalledApps()
    }

    override fun onResume() {
        super.onResume()
        val enabled = isNotificationAccessEnabled()
        binding.cardAccessWarning.visibility = if (enabled) View.GONE else View.VISIBLE
    }

    // =========================================================================
    // Setup
    // =========================================================================

    private fun setupRecyclerView() {
        appAdapter = AppPickerAdapter()
        binding.rvApps.apply {
            layoutManager = LinearLayoutManager(this@SettingsActivity)
            adapter = appAdapter
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
        }
    }

    private fun setupButtons() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnSave.setOnClickListener {
            val selected = appAdapter.getSelectedPackages()
            if (selected.isEmpty()) {
                Toast.makeText(this, "เลือกอย่างน้อย 1 แอพ", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AppState.saveFilterPackages(this, selected)
            Toast.makeText(this, "บันทึกแล้ว (${selected.size} แอพ)", Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.btnOpenSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    // =========================================================================
    // Load installed apps (IO thread — อาจช้า)
    // =========================================================================

    private fun loadInstalledApps() {
        val saved = AppState.getFilterPackages(this)

        uiScope.launch {
            val apps = withContext(Dispatchers.IO) { getInstalledApps(saved) }
            binding.progressBar.visibility = View.GONE
            binding.rvApps.visibility      = View.VISIBLE
            appAdapter.submitList(apps)
        }
    }

    private fun getInstalledApps(selectedPackages: Set<String>): List<AppInfo> {
        val pm = packageManager
        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        return installed
            // กรองเฉพาะแอพที่มี launcher icon (ไม่ใช่ system service)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            // แอพที่ selected ขึ้นก่อนเสมอ
            .sortedWith(compareByDescending<android.content.pm.ApplicationInfo> {
                it.packageName in selectedPackages
            }.thenBy {
                pm.getApplicationLabel(it).toString().lowercase()
            })
            .map { info ->
                AppInfo(
                    appName     = pm.getApplicationLabel(info).toString(),
                    packageName = info.packageName,
                    icon        = pm.getApplicationIcon(info.packageName),
                    selected    = info.packageName in selectedPackages,
                )
            }
    }

    private fun isNotificationAccessEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return flat.contains(packageName)
    }
}

// =============================================================================
// Data model
// =============================================================================

data class AppInfo(
    val appName:     String,
    val packageName: String,
    val icon:        android.graphics.drawable.Drawable,
    var selected:    Boolean,
)

// =============================================================================
// AppPickerAdapter
// =============================================================================

class AppPickerAdapter : RecyclerView.Adapter<AppPickerAdapter.VH>() {

    private var items: List<AppInfo> = emptyList()

    fun submitList(list: List<AppInfo>) { items = list; notifyDataSetChanged() }

    fun getSelectedPackages(): Set<String> =
        items.filter { it.selected }.map { it.packageName }.toSet()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_app_pick, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val ivIcon   : ImageView = view.findViewById(R.id.ivIcon)
        private val tvName   : TextView  = view.findViewById(R.id.tvAppName)
        private val tvPkg    : TextView  = view.findViewById(R.id.tvPackageName)
        private val cbSelect : CheckBox  = view.findViewById(R.id.cbSelected)

        fun bind(app: AppInfo) {
            ivIcon.setImageDrawable(app.icon)
            tvName.text  = app.appName
            tvPkg.text   = app.packageName
            cbSelect.isChecked = app.selected

            // กดทั้ง row เพื่อ toggle
            itemView.setOnClickListener {
                app.selected = !app.selected
                cbSelect.isChecked = app.selected
            }
        }
    }
}
