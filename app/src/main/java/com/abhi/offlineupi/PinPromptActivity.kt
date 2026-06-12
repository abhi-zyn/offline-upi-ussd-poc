package com.abhi.offlineupi

import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Step 5 — secure, per-transaction UPI PIN prompt.
 *
 * The PIN is NEVER stored: it lives only in this Activity's masked field, is handed
 * to PaymentSession as a CharArray, and is wiped right after. FLAG_SECURE blocks
 * screenshots / screen recording while the PIN is on screen.
 */
class PinPromptActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Block screenshots / recents thumbnails while the PIN is visible.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setFinishOnTouchOutside(false)

        val pad = (resources.displayMetrics.density * 24).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(this).apply {
            text = getString(R.string.pin_prompt_title)
            textSize = 18f
        }
        val request = PaymentSession.active
        val subtitle = TextView(this).apply {
            text = if (request != null)
                getString(R.string.pin_prompt_subtitle, request.amount, request.vpa)
            else getString(R.string.pin_prompt_generic)
            setPadding(0, pad / 3, 0, pad / 2)
        }

        val pinField = EditText(this).apply {
            hint = getString(R.string.pin_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

        val confirm = Button(this).apply {
            text = getString(R.string.pin_confirm)
            setOnClickListener {
                val entered = pinField.text
                if (entered.isNullOrEmpty()) {
                    pinField.error = getString(R.string.pin_required)
                    return@setOnClickListener
                }
                val pin = CharArray(entered.length)
                entered.getChars(0, entered.length, pin, 0)
                PaymentSession.providePin(pin)
                // Wipe the field's backing buffer.
                pinField.text.clear()
                finish()
            }
        }

        val cancel = Button(this).apply {
            text = getString(R.string.cancel)
            setOnClickListener {
                PaymentSession.finish(PaymentResult(false, getString(R.string.cancelled_by_user)))
                finish()
            }
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(pinField)
        root.addView(confirm)
        root.addView(cancel)
        setContentView(root)
    }

    // Disable hardware back so the PIN step can't be skipped mid-session by accident.
    @Deprecated("Intentional: block back during PIN entry")
    override fun onBackPressed() { /* swallow */ }
}
