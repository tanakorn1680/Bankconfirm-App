package com.testnotification.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.testnotification.service.KeepAliveService

/**
 * AlarmReceiver — รับ alarm จาก AlarmManager แล้ว restart KeepAliveService
 * ใช้เป็น backup กรณี onDestroy() ล้มเหลว (MIUI/Samsung kill aggressive)
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("AlarmReceiver", "alarm fired — restarting KeepAliveService")
        val svc = Intent(context, KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svc)
        } else {
            context.startService(svc)
        }
    }
}
