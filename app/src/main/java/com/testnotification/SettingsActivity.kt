package com.testnotification

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.testnotification.data.AppState
import com.testnotification.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // โหลด packages ปัจจุบัน
        val current = AppState.getFilterPackages(this)
        binding.etPackages.setText(AppState.packagesToText(current))

        binding.btnBack.setOnClickListener { finish() }

        binding.btnSave.setOnClickListener {
            val text = binding.etPackages.text.toString()
            val packages = AppState.textToPackages(text)
            if (packages.isEmpty()) {
                Toast.makeText(this, "กรอก Package Name อย่างน้อย 1 รายการ", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AppState.saveFilterPackages(this, packages)
            Toast.makeText(this, "บันทึกแล้ว (${packages.size} แอพ)", Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.btnOpenSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        val enabled = isNotificationAccessEnabled()
        binding.tvAccessStatus.text = if (enabled)
            "● Notification Access: เปิดอยู่"
        else
            "● Notification Access: ปิดอยู่ — กดปุ่มด้านล่างเพื่อเปิด"
        binding.tvAccessStatus.setTextColor(
            getColor(if (enabled) R.color.status_on else R.color.status_off)
        )
        binding.btnOpenSettings.visibility =
            if (enabled) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun isNotificationAccessEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return flat.contains(packageName)
    }
}
