package com.testnotification.data

/**
 * AppState — Singleton เก็บ Notification Log แบบ in-memory
 *
 * เพิ่ม updateLastLog() สำหรับอัปเดต match result
 * กลับมาบน log row ล่าสุด หลัง API ตอบกลับ
 */
object AppState {

    const val ACTION_NOTIFICATION_RECEIVED = "com.testnotification.ACTION_NOTIFICATION_RECEIVED"
    const val MAX_LOG_SIZE = 50

    // list เรียงจากใหม่ → เก่า
    private val logs = mutableListOf<NotificationLog>()

    @Synchronized
    fun addLog(log: NotificationLog) {
        logs.add(0, log)
        if (logs.size > MAX_LOG_SIZE) logs.removeAt(logs.size - 1)
    }

    /**
     * updateLastLog — อัปเดต matchResult ของ log ล่าสุด (index 0)
     * ใช้หลัง API ตอบกลับเพื่อแสดงผล match บน UI
     */
    @Synchronized
    fun updateLastLog(matchResult: String) {
        if (logs.isNotEmpty()) {
            logs[0] = logs[0].copy(matchResult = matchResult)
        }
    }

    @Synchronized
    fun getLogs(): List<NotificationLog> = logs.toList()
}
