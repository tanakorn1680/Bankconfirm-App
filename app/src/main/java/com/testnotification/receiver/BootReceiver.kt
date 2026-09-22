package com.testnotification.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.testnotification.service.KeepAliveService

/**
 * BootReceiver — รับ BOOT_COMPLETED แล้ว start KeepAliveService
 * เพื่อให้แอพทำงานพื้นหลังอัตโนมัติหลัง reboot โดยไม่ต้องเปิดแอพ
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") return

        Log.d("BootReceiver", "boot completed — starting KeepAliveService")

        val serviceIntent = Intent(context, KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
