package com.abhi.offlineupi

/**
 * Step 2 — Canara Bank *99# flow, expressed as a state machine.
 *
 * KEY FIX: the option NUMBERS on *99# menus are server-generated and differ by
 * bank/telecom (this is what caused "invalid code received" after a few steps).
 * So instead of hardcoding "press 2 for UPI ID", we now READ the live menu text
 * and pick whichever line actually mentions the option we want. Hardcoded numbers
 * are only used as a last-resort fallback if the menu can't be parsed.
 */
enum class CanaraState {
    START,          // before dialling
    LANGUAGE,       // "1. English 2. Hindi ..."
    MAIN_MENU,      // "1. Send Money 2. Request Money ..."
    SEND_VIA,       // "1. Mobile No 2. UPI ID 3. Account+IFSC ..."
    ENTER_VPA,      // "Enter UPI ID / Virtual Payment Address"
    ENTER_AMOUNT,   // "Enter Amount"
    ENTER_REMARKS,  // "Enter Remarks (optional)"
    CONFIRM,        // "1. Confirm 2. Cancel" style screen (pre-PIN)
    ENTER_PIN,      // "Enter UPI PIN"
    CONFIRMATION,   // final success/failure screen / session end
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
     * Order matters: text-entry screens are checked before generic menus.
     */
    fun classify(dialogText: String): CanaraState {
        val t = dialogText.lowercase()
        return when {
            // Final result screen — check first so "amount" in a receipt isn't mistaken
            // for the amount-entry screen.
            t.contains("success") || t.contains("failed") || t.contains("debited") ||
                t.contains("declined") -> CanaraState.CONFIRMATION
            // PIN entry.
            t.contains("pin") -> CanaraState.ENTER_PIN
            // Language picker.
            t.contains("language") || (t.contains("english") && t.contains("hindi")) -> CanaraState.LANGUAGE
            // Main menu (has both Send Money and Request).
            t.contains("send money") && t.contains("request") -> CanaraState.MAIN_MENU
            // "How do you want to send" menu (lists UPI ID alongside mobile/account).
            t.contains("upi id") && (t.contains("mobile") || t.contains("account") ||
                t.contains("ifsc") || t.contains("beneficiary")) -> CanaraState.SEND_VIA
            // Text-entry: enter the VPA / UPI ID.
            (t.contains("enter") && t.contains("upi") && t.contains("id")) ||
                t.contains("virtual payment address") || t.contains("enter vpa") -> CanaraState.ENTER_VPA
            // Text-entry: amount.
            t.contains("amount") -> CanaraState.ENTER_AMOUNT
            // Text-entry: remarks.
            t.contains("remark") -> CanaraState.ENTER_REMARKS
            // Pre-PIN confirm screen ("1. Confirm 2. Cancel").
            t.contains("confirm") -> CanaraState.CONFIRM
            else -> CanaraState.UNKNOWN
        }
    }

    /**
     * Scan a menu's text and return the option number whose label contains any of
     * the given keywords. Handles lines like "2. UPI ID", "2) UPI ID",
     * "2:UPI ID", "2 - UPI ID" or "2 UPI ID". Returns null if not found.
     */
    fun optionNumber(dialogText: String, vararg keywords: String): String? {
        val lineRegex = Regex("""(?m)^\s*(\d{1,2})\s*[).:\-]?\s+(.+)$""")
        for (m in lineRegex.findAll(dialogText)) {
            val label = m.groupValues[2].lowercase()
            if (keywords.any { label.contains(it.lowercase()) }) return m.groupValues[1]
        }
        return null
    }

    /**
     * Given the current screen + the active request + the LIVE dialog text, return
     * what to type. For menus we derive the number from the on-screen options so we
     * never send a number the menu doesn't have. Fallback numbers are best-guesses.
     *
     * Returns null for states that need special handling (PIN) or that end the flow.
     */
    fun inputFor(state: CanaraState, request: PaymentRequest, dialogText: String): String? = when (state) {
        CanaraState.LANGUAGE -> optionNumber(dialogText, "english") ?: "1"
        CanaraState.MAIN_MENU -> optionNumber(dialogText, "send money", "send") ?: "1"
        CanaraState.SEND_VIA ->
            optionNumber(dialogText, "upi id", "upi-id", "vpa", "virtual payment", "payment address") ?: "2"
        CanaraState.ENTER_VPA -> request.vpa
        CanaraState.ENTER_AMOUNT -> request.amount
        CanaraState.ENTER_REMARKS -> if (request.remarks.isBlank()) "poc" else request.remarks
        CanaraState.CONFIRM -> optionNumber(dialogText, "confirm", "yes", "proceed", "pay") ?: "1"
        CanaraState.ENTER_PIN -> null   // handled by secure prompt, never hardcoded
        else -> null
    }
}
