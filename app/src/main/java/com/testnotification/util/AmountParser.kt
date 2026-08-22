package com.testnotification.util

import java.util.regex.Pattern

/**
 * AmountParser — parse ยอดเงินจาก notification text
 *
 * รองรับรูปแบบที่พบจาก KBank และ TrueMoney:
 *
 * KBank (K PLUS):
 *   "รับโอนเงิน 1,500.00 บาท"
 *   "โอนเงินสำเร็จ 350.00 บ."
 *   "ยอดเงิน 1500 บาท"
 *   "Received THB 1,500.00"
 *   "Transfer THB 350.00"
 *
 * TrueMoney Wallet:
 *   "รับเงิน 150.43 บาท"
 *   "ได้รับเงิน 150.43 บาท จาก"
 *   "รับโอน 150.43 บาท"
 */
object AmountParser {

    // pattern หลัก: ตัวเลขที่มี , หรือ . คั่น ตามด้วย บาท / บ. / THB
    private val PATTERNS = listOf(
        // "1,500.00 บาท" / "1500 บาท" / "1,500 บ."
        Pattern.compile("""([\d,]+\.?\d*)\s*(?:บาท|บ\.)"""),
        // "THB 1,500.00" / "THB 1500"
        Pattern.compile("""THB\s*([\d,]+\.?\d*)"""),
        // "฿1,500.00" / "฿1500"
        Pattern.compile("""฿\s*([\d,]+\.?\d*)"""),
    )

    /**
     * parse ยอดเงินจาก text
     * คืน Double หรือ null ถ้าหาไม่เจอ
     */
    fun parse(text: String): Double? {
        val combined = text.trim()
        for (p in PATTERNS) {
            val m = p.matcher(combined)
            if (m.find()) {
                val raw = m.group(1)?.replace(",", "") ?: continue
                val value = raw.toDoubleOrNull() ?: continue
                if (value > 0) return value
            }
        }
        return null
    }

    /**
     * format สำหรับ log
     * 1500.0 → "฿1,500.00"
     */
    fun format(amount: Double): String =
        "฿%,.2f".format(amount)
}
