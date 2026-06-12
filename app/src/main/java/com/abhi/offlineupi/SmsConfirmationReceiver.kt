package com.abhi.offlineupi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * Step 6 — SMS confirmation reader.
 *
 * Since there is no online callback over USSD, the bank's confirmation SMS is the
 * only authoritative success/failure signal. We only act on SMS while a payment is
 * active, parse the amount + UPI reference number, and report the final result.
 */
class SmsConfirmationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!PaymentSession.isActive()) return

        val body = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            ?.joinToString(separator = " ") { it.messageBody ?: "" }
            ?.trim()
            ?: return
        if (body.isEmpty()) return

        Log.i(PaymentSession.TAG, "Confirmation SMS: $body")
        PaymentSession.finish(parse(body))
    }

    companion object {
        // "Rs.1 debited ... UPI Ref no 412345678901" / "... Ref: 41234" etc.
        private val refRegex = Regex(
            """ref(?:erence)?\s*(?:no|number)?[:.\s]*([0-9]{6,})""",
            RegexOption.IGNORE_CASE,
        )
        private val amountRegex = Regex(
            """(?:rs|inr)\.?\s*([0-9]+(?:\.[0-9]{1,2})?)""",
            RegexOption.IGNORE_CASE,
        )

        fun parse(body: String): PaymentResult {
            val lower = body.lowercase()
            val failed = lower.contains("fail") || lower.contains("declined") ||
                lower.contains("unsuccessful") || lower.contains("not processed")
            val success = !failed && (lower.contains("debited") ||
                lower.contains("success") || lower.contains("paid") ||
                lower.contains("transferred"))
            val ref = refRegex.find(body)?.groupValues?.getOrNull(1)
            val amount = amountRegex.find(body)?.groupValues?.getOrNull(1)
            return when {
                success -> PaymentResult(
                    success = true,
                    message = "Payment successful" + (amount?.let { " ₹$it" } ?: ""),
                    referenceNo = ref,
                )
                failed -> PaymentResult(false, "Payment failed: $body", ref)
                else -> PaymentResult(false, "Could not confirm from SMS:\n$body", ref)
            }
        }
    }
}
