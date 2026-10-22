package com.testnotification.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.testnotification.service.KeepAliveService
import com.testnotification.service.ListenerHealth
import com.testnotification.service.OrderWatcher

/**
 * AlarmReceiver — รับ alarm จาก AlarmManager (ทุก ~1 นาที)
 *
 * ทำ 4 อย่าง โดยไม่ปล่อยให้ exception หลุด (หลุด = โปรเซส crash = listener ตาย):
 * 1. ตั้ง alarm รอบถัดไป (alarm เป็นแบบ one-shot / setAndAllowWhileIdle)
 * 2. พยายาม start KeepAliveService — Android 12+ อาจไม่อนุญาตจากเบื้องหลัง จึงต้อง try/catch
 * 3. ตรวจ NotificationListener และซ่อมถ้าหลุด bind (ทำได้แม้ข้อ 2 ล้มเหลว)
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("AlarmReceiver", "alarm fired")

        KeepAliveService.scheduleAlarm(context.applicationContext)

        try {
            val svc = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        } catch (e: Exception) {
            Log.w("AlarmReceiver", "start KeepAliveService not allowed: ${e.message}")
        }

        ListenerHealth.ensureBound(context.applicationContext, "alarm")

        // เช็กออเดอร์ใหม่ + อ่านแจ้งเตือนที่ค้างอยู่ (ทำเฉพาะตอนมีเน็ต)
        OrderWatcher.tick(context.applicationContext, "alarm")
    }
}
