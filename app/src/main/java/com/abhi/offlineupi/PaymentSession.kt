package com.abhi.offlineupi

import android.util.Log

/**
 * Tiny in-memory coordinator shared between the UI, the USSD controller, the
 * AccessibilityService and the SMS receiver. A real app would use a DI graph +
 * lifecycle-aware state; for a single-Activity PoC a guarded singleton is enough.
 */
object PaymentSession {

    const val TAG = "OfflineUpiPoc"

    @Volatile var active: PaymentRequest? = null
        private set

    /** Set by PinPromptActivity when the user confirms; consumed once then cleared. */
    @Volatile private var pendingPin: CharArray? = null

    /** Callback the UI registers to receive the final result. */
    @Volatile var resultListener: ((PaymentResult) -> Unit)? = null

    fun begin(request: PaymentRequest) {
        active = request
        pendingPin = null
        Log.i(TAG, "Session begin -> vpa=${request.vpa} amount=${request.amount}")
    }

    fun providePin(pin: CharArray) {
        pendingPin = pin
    }

    /** Returns the PIN once, then wipes it from memory. */
    fun consumePin(): CharArray? {
        val p = pendingPin
        pendingPin = null
        return p
    }

    fun finish(result: PaymentResult) {
        Log.i(TAG, "Session finish -> $result")
        active = null
        pendingPin?.fill('\u0000')
        pendingPin = null
        resultListener?.invoke(result)
    }

    fun isActive(): Boolean = active != null
}

data class PaymentResult(
    val success: Boolean,
    val message: String,
    val referenceNo: String? = null,
)
