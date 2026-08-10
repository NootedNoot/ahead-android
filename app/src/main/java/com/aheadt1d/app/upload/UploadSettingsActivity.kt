package com.aheadt1d.app.upload

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aheadt1d.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings screen for the uploader (see UploadCoordinator) - deliberately
 * two-tier: a non-technical person just picks a card (Off / Nightscout /
 * custom webhook) and, for Nightscout, fills in a site URL and pastes a
 * token - that's a complete, working setup. Everything else (the legacy API
 * secret, the device name tag, the webhook's custom auth header) sits behind
 * one "Show advanced settings" toggle so it's available without being in the
 * way of the simple path. The toggle is global, not per-method, since only
 * the currently-selected method's field card is visible at any time anyway.
 */
class UploadSettingsActivity : AppCompatActivity() {

    private lateinit var uploadStatusText: TextView
    private lateinit var methodNoneCard: View
    private lateinit var methodNightscoutCard: View
    private lateinit var methodWebhookCard: View
    private lateinit var methodNoneCheck: View
    private lateinit var methodNightscoutCheck: View
    private lateinit var methodWebhookCheck: View
    private lateinit var nightscoutFields: View
    private lateinit var webhookFields: View
    private lateinit var nightscoutAdvancedFields: View
    private lateinit var webhookAdvancedFields: View
    private lateinit var advancedToggle: TextView

    private lateinit var nightscoutUrlInput: EditText
    private lateinit var nightscoutTokenInput: EditText
    private lateinit var nightscoutSecretInput: EditText
    private lateinit var nightscoutDeviceNameInput: EditText
    private lateinit var webhookUrlInput: EditText
    private lateinit var webhookHeaderNameInput: EditText
    private lateinit var webhookHeaderValueInput: EditText

    private var selectedMethod: UploadMethod = UploadMethod.NONE
    private var advancedShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_upload_settings)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        uploadStatusText = findViewById(R.id.uploadStatusText)

        methodNoneCard = findViewById(R.id.methodNoneCard)
        methodNightscoutCard = findViewById(R.id.methodNightscoutCard)
        methodWebhookCard = findViewById(R.id.methodWebhookCard)
        methodNoneCheck = findViewById(R.id.methodNoneCheck)
        methodNightscoutCheck = findViewById(R.id.methodNightscoutCheck)
        methodWebhookCheck = findViewById(R.id.methodWebhookCheck)
        nightscoutFields = findViewById(R.id.nightscoutFields)
        webhookFields = findViewById(R.id.webhookFields)
        nightscoutAdvancedFields = findViewById(R.id.nightscoutAdvancedFields)
        webhookAdvancedFields = findViewById(R.id.webhookAdvancedFields)
        advancedToggle = findViewById(R.id.advancedToggle)

        nightscoutUrlInput = findViewById(R.id.nightscoutUrlInput)
        nightscoutTokenInput = findViewById(R.id.nightscoutTokenInput)
        nightscoutSecretInput = findViewById(R.id.nightscoutSecretInput)
        nightscoutDeviceNameInput = findViewById(R.id.nightscoutDeviceNameInput)
        webhookUrlInput = findViewById(R.id.webhookUrlInput)
        webhookHeaderNameInput = findViewById(R.id.webhookHeaderNameInput)
        webhookHeaderValueInput = findViewById(R.id.webhookHeaderValueInput)

        methodNoneCard.setOnClickListener { selectMethod(UploadMethod.NONE) }
        methodNightscoutCard.setOnClickListener { selectMethod(UploadMethod.NIGHTSCOUT) }
        methodWebhookCard.setOnClickListener { selectMethod(UploadMethod.WEBHOOK) }
        advancedToggle.setOnClickListener { toggleAdvanced() }

        findViewById<Button>(R.id.saveButton).setOnClickListener { save() }
        findViewById<Button>(R.id.testConnectionButton).setOnClickListener { testConnection() }

        loadPersisted()

        // Live status, not just "as of when this screen opened" - a real
        // upload can land seconds after you open this screen (piggybacking
        // on GlucoseCheckRunner's own cadence, see UploadCoordinator), and
        // "did it actually go through" is exactly what was reported as
        // missing here. Stops automatically once the screen isn't visible
        // (repeatOnLifecycle), same pattern MainActivity's own chart
        // auto-refresh uses.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refreshStatus()
                    delay(5_000L)
                }
            }
        }
    }

    private fun loadPersisted() {
        nightscoutUrlInput.setText(UploadPrefs.nightscoutUrl(this))
        nightscoutTokenInput.setText(UploadPrefs.nightscoutToken(this))
        nightscoutSecretInput.setText(UploadPrefs.nightscoutSecret(this))
        nightscoutDeviceNameInput.setText(UploadPrefs.nightscoutDeviceName(this))
        webhookUrlInput.setText(UploadPrefs.webhookUrl(this))
        webhookHeaderNameInput.setText(UploadPrefs.webhookHeaderName(this))
        webhookHeaderValueInput.setText(UploadPrefs.webhookHeaderValue(this))
        selectMethod(UploadPrefs.method(this))
        refreshStatus()
    }

    /** Answers exactly "is this actually working right now" - reported as
     *  missing after turning the uploader on for the first time with no way
     *  to tell whether a save with the right-looking fields actually landed
     *  a real sync or just silently failed every cycle. Color-coded (not
     *  just worded) so it reads at a glance: green + a timestamp means a
     *  real upload went through recently, red means the last attempt
     *  failed and says why, muted means nothing's happened yet either way. */
    private fun refreshStatus() {
        val method = UploadPrefs.method(this)
        if (method == UploadMethod.NONE) {
            uploadStatusText.text = "Off - not uploading anywhere right now."
            uploadStatusText.setTextColor(ContextCompat.getColor(this, R.color.muted))
            return
        }
        val methodLabel = if (method == UploadMethod.NIGHTSCOUT) "Nightscout" else "webhook"
        val result = UploadPrefs.lastUploadResult(this)
        val at = UploadPrefs.lastUploadAtMs(this)
        val succeeded = result == "OK"

        uploadStatusText.text = when {
            result == null -> "Enabled ($methodLabel) - waiting for the first sync."
            succeeded && at > 0 -> "✓ Connected - last synced to $methodLabel at ${timeFormatter.format(Date(at))}"
            succeeded -> "✓ Connected to $methodLabel"
            at > 0 -> "✗ Last attempt failed at ${timeFormatter.format(Date(at))}: ${result?.removePrefix("FAILED: ")}"
            else -> "✗ Last attempt failed: ${result?.removePrefix("FAILED: ")}"
        }
        uploadStatusText.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    result == null -> R.color.muted
                    succeeded -> R.color.accent2
                    else -> R.color.low
                },
            )
        )
    }

    private fun selectMethod(method: UploadMethod) {
        selectedMethod = method
        methodNoneCheck.visibility = if (method == UploadMethod.NONE) View.VISIBLE else View.INVISIBLE
        methodNightscoutCheck.visibility = if (method == UploadMethod.NIGHTSCOUT) View.VISIBLE else View.INVISIBLE
        methodWebhookCheck.visibility = if (method == UploadMethod.WEBHOOK) View.VISIBLE else View.INVISIBLE
        nightscoutFields.visibility = if (method == UploadMethod.NIGHTSCOUT) View.VISIBLE else View.GONE
        webhookFields.visibility = if (method == UploadMethod.WEBHOOK) View.VISIBLE else View.GONE
    }

    private fun toggleAdvanced() {
        advancedShown = !advancedShown
        advancedToggle.text = if (advancedShown) "Hide advanced settings" else "Show advanced settings"
        nightscoutAdvancedFields.visibility = if (advancedShown) View.VISIBLE else View.GONE
        webhookAdvancedFields.visibility = if (advancedShown) View.VISIBLE else View.GONE
    }

    private fun save() {
        UploadPrefs.saveNightscout(
            this,
            url = nightscoutUrlInput.text.toString(),
            token = nightscoutTokenInput.text.toString(),
            secret = nightscoutSecretInput.text.toString(),
            deviceName = nightscoutDeviceNameInput.text.toString(),
        )
        UploadPrefs.saveWebhook(
            this,
            url = webhookUrlInput.text.toString(),
            headerName = webhookHeaderNameInput.text.toString(),
            headerValue = webhookHeaderValueInput.text.toString(),
        )

        val missing = when (selectedMethod) {
            UploadMethod.NIGHTSCOUT -> nightscoutUrlInput.text.isBlank()
            UploadMethod.WEBHOOK -> webhookUrlInput.text.isBlank()
            UploadMethod.NONE -> false
        }
        if (missing) {
            Toast.makeText(this, "Enter a URL before turning this on", Toast.LENGTH_SHORT).show()
            return
        }

        UploadPrefs.setMethod(this, selectedMethod)
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        refreshStatus()
    }

    /** Uses whatever's currently typed in the fields, not the last-saved
     *  values - lets someone check credentials work before committing to
     *  them (and before this method ever gets a chance to run for real on
     *  the next check cycle). */
    private fun testConnection() {
        val uploader: Uploader = when (selectedMethod) {
            UploadMethod.NIGHTSCOUT -> {
                if (nightscoutUrlInput.text.isBlank()) {
                    Toast.makeText(this, "Enter a site URL first", Toast.LENGTH_SHORT).show()
                    return
                }
                NightscoutUploader.from(
                    baseUrl = nightscoutUrlInput.text.toString(),
                    token = nightscoutTokenInput.text.toString(),
                    secret = nightscoutSecretInput.text.toString(),
                    deviceName = nightscoutDeviceNameInput.text.toString(),
                )
            }
            UploadMethod.WEBHOOK -> {
                if (webhookUrlInput.text.isBlank()) {
                    Toast.makeText(this, "Enter a webhook URL first", Toast.LENGTH_SHORT).show()
                    return
                }
                WebhookUploader.from(
                    url = webhookUrlInput.text.toString(),
                    headerName = webhookHeaderNameInput.text.toString(),
                    headerValue = webhookHeaderValueInput.text.toString(),
                )
            }
            UploadMethod.NONE -> {
                Toast.makeText(this, "Pick Nightscout or a webhook first", Toast.LENGTH_SHORT).show()
                return
            }
        }

        Toast.makeText(this, "Testing…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            when (val result = uploader.testConnection()) {
                is UploadResult.Success -> Toast.makeText(this@UploadSettingsActivity, "Connected successfully", Toast.LENGTH_LONG).show()
                is UploadResult.Failure -> Toast.makeText(this@UploadSettingsActivity, result.detail, Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())

        fun createIntent(context: Context): Intent = Intent(context, UploadSettingsActivity::class.java)
    }
}
