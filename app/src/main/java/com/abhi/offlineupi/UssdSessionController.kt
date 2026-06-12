package com.abhi.offlineupi

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Step 3 — USSD session controller.
 *
 * Android's official TelephonyManager.sendUssdRequest() reliably returns only the
 * FIRST request/response of a session. It cannot drive the multi-step Canara menu
 * by itself. So for the real flow this controller just:
 *   1. kicks off the *99# session, and
 *   2. hands control to the AccessibilityService, which navigates the live dialogs.
 *
 * Two entry points:
 *   - startPayment()/dial(): the accessibility-driven multi-step flow. The PIN is
 *     now collected BEFORE dialling (see MainActivity), so begin() and dial() are
 *     split: the UI calls begin() + shows the PIN prompt, then calls dial().
 *   - sendSingleShot(): a no-accessibility test that fires ONE USSD string and
 *     reports whatever single response comes back.
 */
class UssdSessionController(private val context: Context) {

    private val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    /** Convenience: begin a session and immediately dial (PIN collected mid-flow). */
    fun startPayment(request: PaymentRequest) {
        PaymentSession.begin(request)
        dial()
    }

    /**
     * Fire the *99# request for an ALREADY-begun session. Used after the PIN has been
     * collected up-front; the AccessibilityService injects that pre-supplied PIN when
     * the bank's PIN screen appears.
     */
    @SuppressLint("MissingPermission") // CALL_PHONE is checked by the caller (MainActivity).
    fun dial() {
        val callback = object : TelephonyManager.UssdResponseCallback() {
            override fun onReceiveUssdResponse(
                tm: TelephonyManager,
                request2: String,
                response: CharSequence,
            ) {
                // First menu screen. The AccessibilityService will also see it and
                // take over navigation; we just log here for debugging.
                Log.i(PaymentSession.TAG, "USSD first response: $response")
            }

            override fun onReceiveUssdResponseFailed(
                tm: TelephonyManager,
                request2: String,
                failureCode: Int,
            ) {
                Log.w(PaymentSession.TAG, "USSD request failed code=$failureCode")
                // failureCode often fires even when the dialog is shown to the user,
                // so we do NOT abort here — the AccessibilityService remains the
                // authority on session progress and completion.
            }
        }

        try {
            telephonyManager.sendUssdRequest(USSD_CODE, callback, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) {
            PaymentSession.finish(
                PaymentResult(false, "Missing CALL_PHONE permission: ${e.message}")
            )
        } catch (e: Exception) {
            PaymentSession.finish(
                PaymentResult(false, "Failed to start USSD: ${e.message}")
            )
        }
    }

    /**
     * Single-call test path — NO AccessibilityService involved.
     *
     * Fires exactly one USSD string and surfaces the single response (or failure) via
     * callbacks. This proves whether (a) USSD works on this SIM at all, and (b)
     * whether a pre-built \"concatenated\" string is accepted. Note: TelephonyManager
     * only returns the FIRST response, so this cannot complete a true multi-step
     * payment — it is a diagnostic/experiment tool.
     */
    @SuppressLint("MissingPermission") // CALL_PHONE is checked by the caller (MainActivity).
    fun sendSingleShot(
        ussd: String,
        onResponse: (String) -> Unit,
        onFailed: (Int) -> Unit,
    ) {
        val callback = object : TelephonyManager.UssdResponseCallback() {
            override fun onReceiveUssdResponse(
                tm: TelephonyManager,
                request2: String,
                response: CharSequence,
            ) {
                Log.i(PaymentSession.TAG, "Single-shot USSD response: $response")
                onResponse(response.toString())
            }

            override fun onReceiveUssdResponseFailed(
                tm: TelephonyManager,
                request2: String,
                failureCode: Int,
            ) {
                Log.w(PaymentSession.TAG, "Single-shot USSD failed code=$failureCode")
                onFailed(failureCode)
            }
        }

        try {
            telephonyManager.sendUssdRequest(ussd, callback, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) {
            onFailed(-1)
        } catch (e: Exception) {
            onFailed(-2)
        }
    }

    companion object {
        const val USSD_CODE = "*99#"
    }
}
