package com.abhi.offlineupi

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Step 4 — the core of the PoC.
 *
 * Reacts to each system USSD dialog: reads the text, classifies the screen via
 * CanaraFlow, types the next input into the dialog's EditText and taps the positive
 * (Send) button. Repeats until the flow completes. When the PIN screen appears it
 * pauses and launches the secure PinPromptActivity instead of typing a stored PIN.
 */
class UssdAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastHandledText: String = ""
    private var waitingForPin = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!PaymentSession.isActive()) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        val source = rootInActiveWindow ?: return
        if (!looksLikeUssdDialog(source)) return

        val dialogText = collectText(source).trim()
        if (dialogText.isEmpty() || dialogText == lastHandledText) return
        lastHandledText = dialogText

        val request = PaymentSession.active ?: return
        val state = CanaraFlow.classify(dialogText)
        Log.i(PaymentSession.TAG, "USSD screen [$state]: $dialogText")

        when (state) {
            CanaraState.ENTER_PIN -> requestPinThenInject(source)
            CanaraState.CONFIRMATION -> handleConfirmation(dialogText)
            CanaraState.UNKNOWN -> abort("Unexpected screen — aborting for safety:\n$dialogText")
            else -> {
                val input = CanaraFlow.inputFor(state, request)
                if (input != null) respond(source, input)
            }
        }
    }

    /** When the PIN screen shows, ask the user (never store), then inject the result. */
    private fun requestPinThenInject(dialogRoot: AccessibilityNodeInfo) {
        if (waitingForPin) return
        waitingForPin = true
        val pin = PaymentSession.consumePin()
        if (pin != null) {
            injectAndClear(dialogRoot, pin)
            waitingForPin = false
        } else {
            // Launch the secure prompt; it calls back via PaymentSession.providePin,
            // and a scheduled retry picks the PIN up.
            val intent = Intent(this, PinPromptActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            schedulePinRetry(dialogRoot, attemptsLeft = 60)
        }
    }

    private fun schedulePinRetry(dialogRoot: AccessibilityNodeInfo, attemptsLeft: Int) {
        if (attemptsLeft <= 0) {
            abort("PIN entry timed out.")
            waitingForPin = false
            return
        }
        mainHandler.postDelayed({
            val pin = PaymentSession.consumePin()
            if (pin != null) {
                injectAndClear(rootInActiveWindow ?: dialogRoot, pin)
                waitingForPin = false
            } else {
                schedulePinRetry(dialogRoot, attemptsLeft - 1)
            }
        }, 500)
    }

    private fun injectAndClear(dialogRoot: AccessibilityNodeInfo, pin: CharArray) {
        respond(dialogRoot, String(pin))
        pin.fill('\u0000')
    }

    private fun handleConfirmation(text: String) {
        // The on-screen text is a hint; the authoritative result comes from the SMS.
        val success = text.lowercase().let { it.contains("success") || it.contains("debited") }
        if (success) {
            Log.i(PaymentSession.TAG, "On-screen confirmation positive; awaiting SMS for ref no.")
        } else {
            PaymentSession.finish(PaymentResult(false, "USSD reported failure:\n$text"))
        }
        lastHandledText = ""
    }

    private fun abort(reason: String) {
        Log.w(PaymentSession.TAG, reason)
        performGlobalAction(GLOBAL_ACTION_BACK) // dismiss the dialog
        PaymentSession.finish(PaymentResult(false, reason))
        lastHandledText = ""
    }

    /** Find the EditText, set the value, then click the positive/Send button. */
    private fun respond(dialogRoot: AccessibilityNodeInfo, value: String) {
        val edit = findFirst(dialogRoot) { it.className?.contains("EditText") == true }
        if (edit == null) {
            Log.w(PaymentSession.TAG, "No EditText found in dialog; cannot respond.")
            return
        }
        val args = android.os.Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                value,
            )
        }
        edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        // Give the framework a beat, then click Send.
        mainHandler.postDelayed({ clickSend(rootInActiveWindow ?: dialogRoot) }, 250)
    }

    private fun clickSend(dialogRoot: AccessibilityNodeInfo) {
        // Positive button text varies ("Send", "OK", "SEND"). Match loosely.
        val button = findFirst(dialogRoot) { node ->
            val txt = node.text?.toString()?.lowercase() ?: ""
            node.isClickable && (txt == "send" || txt == "ok" || txt.contains("send"))
        } ?: findFirst(dialogRoot) { it.isClickable && it.className?.contains("Button") == true }
        button?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun looksLikeUssdDialog(root: AccessibilityNodeInfo): Boolean {
        val pkg = root.packageName?.toString() ?: return false
        // USSD dialogs are rendered by the phone/telephony stack.
        return pkg.contains("phone") || pkg.contains("telephony") ||
            pkg.contains("dialer") || pkg.contains("telecom") || pkg.contains("server.telecom")
    }

    private fun collectText(node: AccessibilityNodeInfo?, sb: StringBuilder = StringBuilder()): String {
        if (node == null) return sb.toString()
        node.text?.let { if (it.isNotBlank()) sb.append(it).append('\n') }
        for (i in 0 until node.childCount) collectText(node.getChild(i), sb)
        return sb.toString()
    }

    private fun findFirst(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val found = findFirst(node.getChild(i), predicate)
            if (found != null) return found
        }
        return null
    }

    override fun onInterrupt() { /* no-op */ }
}
