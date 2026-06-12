package com.abhi.offlineupi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.abhi.offlineupi.databinding.ActivityMainBinding

/**
 * Step 7 — minimal test harness.
 *
 * Single screen: UPI ID + amount, two pay buttons, a result label, plus helpers to
 * enable permissions and the Accessibility service.
 *
 *  - "Pay via *99# (Accessibility)": the real multi-step flow. The UPI PIN is now
 *    collected UP-FRONT (before the USSD session starts); the AccessibilityService
 *    injects it when the bank's PIN screen appears.
 *  - "Test single-call (no Accessibility)": fires ONE USSD request and shows the raw
 *    response — a diagnostic to check USSD works and to experiment with concatenated
 *    strings. It does not use the Accessibility service.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var controller: UssdSessionController

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it }
        toast(if (granted) getString(R.string.perms_granted) else getString(R.string.perms_denied))
    }

    // Launches the secure PIN prompt BEFORE dialling. Only when the user confirms a
    // PIN (RESULT_OK) do we start the *99# session.
    private val pinLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            binding.txtResult.text = getString(R.string.starting)
            controller.dial()
        }
        // On cancel, PinPromptActivity already reported the cancellation result.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        controller = UssdSessionController(this)

        PaymentSession.resultListener = { result -> runOnUiThread { showResult(result) } }

        binding.btnPerms.setOnClickListener { requestRuntimePermissions() }
        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast(getString(R.string.enable_service_hint))
        }
        binding.btnPay.setOnClickListener { attemptPayment() }
        binding.btnPayDirect.setOnClickListener { attemptDirectPayment() }
    }

    override fun onResume() {
        super.onResume()
        binding.txtStatus.text = buildString {
            append(getString(R.string.label_perms)).append(": ")
            append(if (hasAllPermissions()) "✅" else "❌").append("\n")
            append(getString(R.string.label_service)).append(": ")
            append(if (isAccessibilityEnabled()) "✅" else "❌")
        }
    }

    /** Real flow: validate -> begin session -> collect PIN -> (on OK) dial *99#. */
    private fun attemptPayment() {
        if (!hasAllPermissions()) { toast(getString(R.string.grant_perms_first)); return }
        if (!isAccessibilityEnabled()) { toast(getString(R.string.enable_service_first)); return }
        if (PaymentSession.isActive()) { toast(getString(R.string.payment_in_progress)); return }

        val vpa = binding.inputVpa.text?.toString()?.trim().orEmpty()
        val amountStr = binding.inputAmount.text?.toString()?.trim().orEmpty()
        val amount = amountStr.toDoubleOrNull()
        when {
            vpa.isEmpty() || !vpa.contains("@") -> binding.inputVpa.error = getString(R.string.invalid_vpa)
            amount == null || amount <= 0 -> binding.inputAmount.error = getString(R.string.invalid_amount)
            amount > 5000 -> binding.inputAmount.error = getString(R.string.over_limit)
            else -> {
                // Begin the session first (so the PIN prompt can show amount + payee),
                // then collect the PIN up-front. dial() runs only after RESULT_OK.
                PaymentSession.begin(PaymentRequest(vpa = vpa, amount = amountStr))
                pinLauncher.launch(Intent(this, PinPromptActivity::class.java))
            }
        }
    }

    /** Diagnostic flow: fire one USSD string, no Accessibility service. */
    private fun attemptDirectPayment() {
        if (!hasAllPermissions()) { toast(getString(R.string.grant_perms_first)); return }
        if (PaymentSession.isActive()) { toast(getString(R.string.payment_in_progress)); return }

        val vpa = binding.inputVpa.text?.toString()?.trim().orEmpty()
        val amountStr = binding.inputAmount.text?.toString()?.trim().orEmpty()
        val amount = amountStr.toDoubleOrNull()
        if (amount == null || amount <= 0) {
            binding.inputAmount.error = getString(R.string.invalid_amount); return
        }

        // USSD strings may contain only digits, * and #. A real VPA (with @ and .)
        // cannot be encoded, so for a UPI ID we can only fire plain *99# to show the
        // first menu. If the recipient field is a plain mobile number, we can build a
        // concatenated send-money string to experiment with.
        val digits = vpa.filter { it.isDigit() }
        val ussd: String = if (vpa.isNotEmpty() && !vpa.contains("@") && digits.length in 10..12) {
            "*99*1*1*$digits*$amountStr#"
        } else {
            toast(getString(R.string.direct_vpa_note))
            "*99#"
        }

        binding.txtResult.text = getString(R.string.direct_sending, ussd)
        controller.sendSingleShot(
            ussd = ussd,
            onResponse = { resp -> runOnUiThread { binding.txtResult.text = getString(R.string.direct_response, resp) } },
            onFailed = { code -> runOnUiThread { binding.txtResult.text = getString(R.string.direct_failed, code) } },
        )
    }

    private fun showResult(result: PaymentResult) {
        val icon = if (result.success) "✅" else "❌"
        binding.txtResult.text = buildString {
            append(icon).append(' ').append(result.message)
            result.referenceNo?.let { append("\n").append(getString(R.string.ref_no)).append(": ").append(it) }
        }
    }

    private fun requestRuntimePermissions() {
        permLauncher.launch(
            arrayOf(
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_SMS,
                Manifest.permission.RECEIVE_SMS,
            )
        )
    }

    private fun hasAllPermissions(): Boolean = listOf(
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_SMS,
    ).all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = "$packageName/${UssdAccessibilityService::class.java.name}"
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        if (!am.isEnabled) return false
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        if (PaymentSession.resultListener != null) PaymentSession.resultListener = null
    }
}
