package com.testnotification.data

/**
 * NotificationLog — in-memory model
 *
 * เพิ่ม matchResult: แสดงผลการจับคู่ order
 *   null   = ยังไม่ได้ส่ง API (ไม่ใช่ notification ธนาคาร)
 *   "…กำลังจับคู่..."          = กำลังจับคู่
 *   "จับคู่สำเร็จ …"           = จับคู่สำเร็จ
 *   "Match แล้ว รอ admin …"    = รอแอดมินอนุมัติ
 *   "ไม่พบ order …"            = ไม่พบ order
 *   "Error: …"                = error
 *
 * หมายเหตุ: MainActivity.kt (resultKind) ใช้คำข้างบนเลือกสีของพิลล์ผลลัพธ์
 * ถ้าเปลี่ยนข้อความใน TestNotificationService ให้แก้ resultKind ตาม
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
    val key:         String  = "",    // ใช้อ้างอิงอัปเดตผลจับคู่ของแจ้งเตือนนี้ (sbn.key|postTime)
)
