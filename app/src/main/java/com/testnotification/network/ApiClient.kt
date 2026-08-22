package com.testnotification.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.testnotification.data.PendingOrder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object ApiClient {

    private const val TAG      = "ApiClient"
    private const val API_BASE = "https://scid-ecru.vercel.app"
    private const val API_PATH = "/api/bank-amount"
    private const val TOKEN    = "mytoken2026"
    private const val TIMEOUT  = 30L

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val gson      = Gson()
    private val client    = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT, TimeUnit.SECONDS)
        .build()

    // =========================================================================
    // GET /api/bank-amount?action=pending
    // =========================================================================

    sealed class PendingOrdersResult {
        data class Success(val orders: List<PendingOrder>) : PendingOrdersResult()
        data class Error(val message: String)              : PendingOrdersResult()
    }

    fun fetchPendingOrders(): PendingOrdersResult {
        val request = Request.Builder()
            .url("$API_BASE$API_PATH?action=pending")
            .addHeader("Authorization", "Bearer $TOKEN")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string() ?: "{}"
                if (!resp.isSuccessful) return PendingOrdersResult.Error("Server ${resp.code}")
                val json = gson.fromJson(body, JsonObject::class.java)
                val arr: JsonArray = json.getAsJsonArray("orders") ?: JsonArray()
                val orders = mutableListOf<PendingOrder>()
                for (el in arr) {
                    val o = el.asJsonObject
                    orders.add(PendingOrder(
                        id           = o.get("id")?.asString ?: continue,
                        productLabel = o.get("product_label")?.asString ?: "",
                        amount       = o.get("amount")?.asDouble ?: 0.0,
                        uniqueAmount = o.get("unique_amount")?.asDouble ?: 0.0,
                        createdAt    = o.get("created_at")?.asString ?: "",
                        userEmail    = o.get("user_email")?.asString ?: ""
                    ))
                }
                PendingOrdersResult.Success(orders)
            }
        } catch (e: IOException) {
            Log.w(TAG, "fetchPendingOrders network error: ${e.message}")
            PendingOrdersResult.Error(e.message ?: "Network error")
        }
    }

    // =========================================================================
    // POST /api/bank-amount
    // Body: { amount, currency, bank, timestamp, notification_id, is_test }
    // =========================================================================

    sealed class ConfirmResult {
        /** matched_and_approved — จับคู่และ approve สำเร็จ */
        data class Matched(val orderId: String)     : ConfirmResult()
        /** saved, no_match — บันทึกแล้วแต่หา order ไม่เจอ */
        object NoMatch                              : ConfirmResult()
        /** matched_awaiting_review — match แล้วแต่ approve ไม่ได้ (out of stock ฯลฯ) */
        data class AwaitingReview(val orderId: String, val reason: String?) : ConfirmResult()
        /** duplicate — notification ซ้ำ ignore */
        object Duplicate                            : ConfirmResult()
        /** test_received */
        object TestReceived                         : ConfirmResult()
        /** server/network error */
        data class Failure(val message: String)     : ConfirmResult()
    }

    /**
     * confirmPayment — ส่ง notification ไปให้ backend จับคู่ order
     *
     * @param amount         ยอดเงินที่ parse จาก notification (double)
     * @param bank           "kbank" | "truemoney" | "test"
     * @param timestampSec   sbn.postTime / 1000
     * @param notificationId unique id สร้างจาก pkg+postTime+amount
     * @param isTest         true = ทดสอบเท่านั้น ไม่ match order จริง
     */
    fun confirmPayment(
        amount: Double,
        bank: String,
        timestampSec: Long,
        notificationId: String,
        isTest: Boolean = false,
    ): ConfirmResult {
        val payload = JSONObject().apply {
            put("amount",          amount)
            put("currency",        "THB")
            put("bank",            bank)
            put("timestamp",       timestampSec)
            put("notification_id", notificationId)
            put("is_test",         isTest)
        }

        Log.d(TAG, "POST $API_PATH → $payload")

        val request = Request.Builder()
            .url("$API_BASE$API_PATH")
            .addHeader("Authorization", "Bearer $TOKEN")
            .post(payload.toString().toRequestBody(JSON_TYPE))
            .build()

        return try {
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string() ?: "{}"
                Log.d(TAG, "POST response [${resp.code}]: $body")

                if (!resp.isSuccessful) {
                    return ConfirmResult.Failure("Server ${resp.code}: $body")
                }

                val json = try { JSONObject(body) } catch (e: Exception) {
                    return ConfirmResult.Failure("Invalid JSON: $body")
                }

                when (val status = json.optString("status")) {
                    "matched_and_approved"  ->
                        ConfirmResult.Matched(json.optString("order_id", "?"))
                    "matched_awaiting_review" ->
                        ConfirmResult.AwaitingReview(
                            orderId = json.optString("order_id", "?"),
                            reason  = json.optString("reason")
                        )
                    "saved, no_match"       -> ConfirmResult.NoMatch
                    "duplicate, ignored"    -> ConfirmResult.Duplicate
                    "test_received"         -> ConfirmResult.TestReceived
                    else -> ConfirmResult.Failure("Unknown status: $status")
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "confirmPayment network error: ${e.message}")
            ConfirmResult.Failure(e.message ?: "Network error")
        }
    }
}
