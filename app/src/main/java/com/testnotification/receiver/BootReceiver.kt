package com.testnotification.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.testnotification.service.KeepAliveService
import com.testnotification.service.ListenerHealth

/**
 * BootReceiver — รับ BOOT_COMPLETED แล้ว start KeepAliveService
 * เพื่อให้แอพทำงานพื้นหลังอัตโนมัติหลัง reboot โดยไม่ต้องเปิดแอพ
 * (try/catch ทุกจุด — exception หลุดจาก receiver = โปรเซส crash)
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") return

        Log.d("BootReceiver", "boot completed — starting KeepAliveService")

        try {
            val serviceIntent = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.w("BootReceiver", "start KeepAliveService failed: ${e.message}")
        }

        ListenerHealth.ensureBound(context.applicationContext, "boot")
    }
}
