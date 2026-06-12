package com.abhi.offlineupi

/**
 * Step 2 — Canara Bank *99# flow, expressed as a state machine.
 *
 * VERIFIED against real on-device screens (Canara Bank, *99#):
 *
 *   MAIN_MENU   "Select Option: CANARA BANK
 *                1. Send Money
 *                2. Request Money
 *                3. Check Balance
 *                4. My Profile
 *                5. Pending Requests
 *                6. Transactions
 *                7. UPI PIN"                      -> press 1 (Send Money)
 *
 *   SEND_VIA    "Send Money to:
 *                1. Mobile No.
 *                3. UPI ID            <-- note: option 3, and there is NO 2!
 *                4. Saved Beneficiary
 *                5. IFSC, A/C No.
 *                00.Back"                          -> press 3 (UPI ID)
 *
 *   ENTER_VPA   "Enter UPI ID"                       -> type the VPA
 *   ENTER_AMOUNT"Paying <name>, Enter Amount in Rs." -> type amount
 *   ENTER_REMARKS"Enter a remark (Enter 1 to skip)"  -> type "1" to skip
 *   ENTER_PIN   "You are paying to UPI ID-...@...
 *                Amount N
 *                Enter UPI Pin to proceed"           -> enter UPI PIN
 *
 * The earlier "invalid code received" bug was caused by pressing "2" for UPI ID;
 * Canara skips 2 entirely. We now READ the live menu and press whichever number
 * actually maps to UPI ID / Send Money, with Canara-correct hardcoded fallbacks.
 */
enum class CanaraState {
    START,          // before dialling
    LANGUAGE,       // "1. English 2. Hindi ..." (skipped once language is set)
    MAIN_MENU,      // "Select Option: CANARA BANK / 1. Send Money ..."
    SEND_VIA,       // "Send Money to: / 1. Mobile No. / 3. UPI ID ..."
    ENTER_VPA,      // "Enter UPI ID"
    ENTER_AMOUNT,   // "Paying <name>, Enter Amount in Rs."
    ENTER_REMARKS,  // "Enter a remark (Enter 1 to skip)"
    CONFIRM,        // "1. Confirm 2. Cancel" style screen (pre-PIN), if shown
    ENTER_PIN,      // "Enter UPI Pin to proceed"
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
     * Order matters: result/PIN/text-entry screens are checked before generic menus.
     */
    fun classify(dialogText: String): CanaraState {
        val t = dialogText.lowercase()
        return when {
            // Final result screen — check first so "amount" in a receipt isn't mistaken
            // for the amount-entry screen.
            t.contains("success") || t.contains("failed") || t.contains("debited") ||
                t.contains("declined") -> CanaraState.CONFIRMATION
            // PIN entry — checked before VPA since this screen also contains "UPI ID".
            t.contains("pin") -> CanaraState.ENTER_PIN
            // Language picker (only on first-ever use).
            t.contains("language") || (t.contains("english") && t.contains("hindi")) -> CanaraState.LANGUAGE
            // Main menu ("Select Option: ... 1. Send Money 2. Request Money ...").
            t.contains("send money") && t.contains("request") -> CanaraState.MAIN_MENU
            // "Send Money to:" menu (lists UPI ID alongside mobile/account/beneficiary).
            t.contains("upi id") && (t.contains("mobile") || t.contains("account") ||
                t.contains("ifsc") || t.contains("beneficiary")) -> CanaraState.SEND_VIA
            // Text-entry: enter the VPA / UPI ID.
            (t.contains("enter") && t.contains("upi") && t.contains("id")) ||
                t.contains("virtual payment address") || t.contains("enter vpa") -> CanaraState.ENTER_VPA
            // Text-entry: remarks ("Enter a remark (Enter 1 to skip)").
            t.contains("remark") -> CanaraState.ENTER_REMARKS
            // Text-entry: amount ("Paying <name>, Enter Amount in Rs.").
            t.contains("amount") -> CanaraState.ENTER_AMOUNT
            // Pre-PIN confirm screen ("1. Confirm 2. Cancel"), if the bank shows one.
            t.contains("confirm") -> CanaraState.CONFIRM
            else -> CanaraState.UNKNOWN
        }
    }

    /**
     * Scan a menu's text and return the option number whose label contains any of
     * the given keywords. Handles lines like "3. UPI ID", "3) UPI ID",
     * "3:UPI ID", "3 - UPI ID" or "3 UPI ID". Returns null if not found.
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
     * never send a number the menu doesn't have. Fallbacks are Canara-verified.
     *
     * Returns null for states that need special handling (PIN) or that end the flow.
     */
    fun inputFor(state: CanaraState, request: PaymentRequest, dialogText: String): String? = when (state) {
        CanaraState.LANGUAGE -> optionNumber(dialogText, "english") ?: "1"
        CanaraState.MAIN_MENU -> optionNumber(dialogText, "send money", "send") ?: "1"
        CanaraState.SEND_VIA ->
            // Canara: UPI ID is option 3 (NOT 2 — there is no 2).
            optionNumber(dialogText, "upi id", "upi-id", "vpa", "virtual payment", "payment address") ?: "3"
        CanaraState.ENTER_VPA -> request.vpa
        CanaraState.ENTER_AMOUNT -> request.amount
        // Screen says "Enter 1 to skip"; type the remark if given, else skip with "1".
        CanaraState.ENTER_REMARKS -> request.remarks.ifBlank { "1" }
        CanaraState.CONFIRM -> optionNumber(dialogText, "confirm", "yes", "proceed", "pay") ?: "1"
        CanaraState.ENTER_PIN -> null   // handled by secure prompt, never hardcoded
        else -> null
    }
}
