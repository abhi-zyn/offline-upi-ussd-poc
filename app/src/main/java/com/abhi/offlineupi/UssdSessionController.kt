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
 * by itself. So this controller's only job is to:
 *   1. kick off the *99# session, and
 *   2. hand control to the AccessibilityService, which navigates the live dialogs.
 *
 * On many OEM builds, simply firing the request causes the system USSD dialog to
 * appear — which is exactly what the AccessibilityService listens for.
 */
class UssdSessionController(private val context: Context) {

    private val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    @SuppressLint("MissingPermission") // CALL_PHONE is checked by the caller (MainActivity).
    fun startPayment(request: PaymentRequest) {
        PaymentSession.begin(request)

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

    companion object {
        const val USSD_CODE = "*99#"
    }
}
