package com.abhi.offlineupi

/**
 * Step 2 — Canara Bank *99# menu map, expressed as a state machine.
 *
 * IMPORTANT: The prompts below are a BEST-GUESS template. The *99# menu wording,
 * option numbers and ordering are server-generated and can differ by bank/telecom
 * and change over time. You MUST run *99# manually on the target SIM once, record
 * each screen verbatim, and adjust [classify] + [inputFor] accordingly. This map is
 * the single source of truth the AccessibilityService matches against.
 */
enum class CanaraState {
    START,          // before dialling
    LANGUAGE,       // "1. English 2. Hindi ..."
    MAIN_MENU,      // "1. Send Money 2. Request Money ..."
    SEND_VIA,       // "1. Mobile No 2. UPI ID 3. Account+IFSC ..."
    ENTER_VPA,      // "Enter UPI ID / Virtual Payment Address"
    ENTER_AMOUNT,   // "Enter Amount"
    ENTER_REMARKS,  // "Enter Remarks (optional)"
    ENTER_PIN,      // "Enter UPI PIN"
    CONFIRMATION,   // final screen / session end
    UNKNOWN,        // safety net — abort the session
}

/**
 * Holds the recipient + amount for the in-flight payment. The PIN is deliberately
 * NOT stored here — it is requested per transaction (see PinPromptActivity) and
 * injected straight into the dialog, then cleared.
 */
data class PaymentRequest(
    val vpa: String,
    val amount: String,
    val remarks: String = "",
)

object CanaraFlow {

    /**
     * Classify a USSD dialog by its text. Matching is intentionally tolerant
     * (lowercase + keyword contains) so minor wording tweaks don't break it.
     */
    fun classify(dialogText: String): CanaraState {
        val t = dialogText.lowercase()
        return when {
            t.contains("language") || (t.contains("english") && t.contains("hindi")) -> CanaraState.LANGUAGE
            t.contains("send money") && t.contains("request") -> CanaraState.MAIN_MENU
            t.contains("upi id") && (t.contains("mobile") || t.contains("account")) -> CanaraState.SEND_VIA
            t.contains("enter") && t.contains("upi") && t.contains("id") -> CanaraState.ENTER_VPA
            t.contains("virtual payment address") || t.contains("enter vpa") -> CanaraState.ENTER_VPA
            t.contains("amount") -> CanaraState.ENTER_AMOUNT
            t.contains("remark") -> CanaraState.ENTER_REMARKS
            t.contains("pin") -> CanaraState.ENTER_PIN
            t.contains("success") || t.contains("failed") || t.contains("debited") -> CanaraState.CONFIRMATION
            else -> CanaraState.UNKNOWN
        }
    }

    /**
     * Given the current screen and the active request, return the text to type.
     * Returns null for states that need special handling (PIN) or that end the flow.
     *
     * NOTE: The option numbers ("1", "2") are placeholders — confirm them against the
     * real Canara menus and edit here.
     */
    fun inputFor(state: CanaraState, request: PaymentRequest): String? = when (state) {
        CanaraState.LANGUAGE -> "1"          // English
        CanaraState.MAIN_MENU -> "1"         // Send Money
        CanaraState.SEND_VIA -> "2"          // pay to UPI ID / VPA
        CanaraState.ENTER_VPA -> request.vpa
        CanaraState.ENTER_AMOUNT -> request.amount
        CanaraState.ENTER_REMARKS -> if (request.remarks.isBlank()) "poc" else request.remarks
        CanaraState.ENTER_PIN -> null       // handled by secure prompt, never hardcoded
        else -> null
    }
}
