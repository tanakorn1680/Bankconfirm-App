package com.testnotification.data

import android.content.Context

object AppState {

    const val ACTION_NOTIFICATION_RECEIVED = "com.testnotification.ACTION_NOTIFICATION_RECEIVED"
    const val MAX_LOG_SIZE = 50

    private const val PREFS_NAME    = "payment_prefs"
    private const val KEY_PACKAGES  = "filter_packages"

    // Default — KBank + TrueMoney
    private val DEFAULT_PACKAGES = setOf(
        "com.kasikorn.retail.mbanking.wap",
        "th.co.truemoney.wallet",
        "com.truemoney.android.wallet",
    )

    // ── Notification log ─────────────────────────────────────────────────────

    private val logs = mutableListOf<NotificationLog>()

    @Synchronized fun addLog(log: NotificationLog) {
        logs.add(0, log)
        if (logs.size > MAX_LOG_SIZE) logs.removeAt(logs.size - 1)
    }

    @Synchronized fun updateLastLog(matchResult: String) {
        if (logs.isNotEmpty()) logs[0] = logs[0].copy(matchResult = matchResult)
    }

    /** อัปเดตผลจับคู่ของแจ้งเตือนตาม key (แม่นกว่า updateLastLog เมื่อมีหลายอันเข้าพร้อมกัน) */
    @Synchronized fun updateLogResult(key: String, matchResult: String) {
        val i = logs.indexOfFirst { it.key == key }
        if (i >= 0) logs[i] = logs[i].copy(matchResult = matchResult)
    }

    @Synchronized fun findLog(key: String): NotificationLog? =
        if (key.isEmpty()) null else logs.firstOrNull { it.key == key }

    @Synchronized fun getLogs(): List<NotificationLog> = logs.toList()

    // ── Package filter (persistent) ──────────────────────────────────────────

    /** คืน set ของ package ที่จะ match กับ order */
    fun getFilterPackages(ctx: Context): Set<String> {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(KEY_PACKAGES, null)
        return saved ?: DEFAULT_PACKAGES
    }

    fun saveFilterPackages(ctx: Context, packages: Set<String>) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_PACKAGES, packages).apply()
    }

    /** แปลง set → string หลายบรรทัดสำหรับ EditText */
    fun packagesToText(packages: Set<String>) = packages.sorted().joinToString("\n")

    /** แปลง string หลายบรรทัดจาก EditText → set (กรองบรรทัดว่าง) */
    fun textToPackages(text: String): Set<String> =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
