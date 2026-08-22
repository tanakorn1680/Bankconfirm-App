package com.testnotification.data

/**
 * NotificationLog — in-memory model
 *
 * เพิ่ม matchResult: แสดงผลการจับคู่ order
 *   null   = ยังไม่ได้ส่ง API (ไม่ใช่ notification ธนาคาร)
 *   "⏳…"  = กำลังจับคู่
 *   "✅…"  = จับคู่สำเร็จ
 *   "❌…"  = ไม่พบ order
 *   "💥…"  = error
 */
data class NotificationLog(
    val timeMs:      Long,
    val packageName: String,
    val appName:     String,
    val title:       String,
    val text:        String,
    val bigText:     String,
    val subText:     String,
    val matchResult: String? = null,  // null = ไม่ใช่ธนาคาร
)
