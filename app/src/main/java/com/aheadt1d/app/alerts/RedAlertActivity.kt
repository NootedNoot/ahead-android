package com.aheadt1d.app.alerts

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.aheadt1d.app.R
import com.aheadt1d.app.emergency.EmergencyAlertRepository
import com.aheadt1d.app.emergency.EmergencyAlertType
import com.aheadt1d.app.emergency.EmergencyContact
import com.aheadt1d.app.emergency.EmergencyContactsPrefs
import com.aheadt1d.app.notifications.GlucoseTrendArrow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The red-severity takeover screen, launched only via the full-screen intent
 * on the red alert notification (exported=false; never started from code -
 * background activity starts are blocked, the FSI is the one sanctioned
 * takeover path). showWhenLocked/turnScreenOn live in both the manifest and
 * here in code - some OEMs honor one but not the other.
 *
 * Also hosts the Emergency Contact Alert entry point: a shield button that,
 * when the feature is enabled and at least one contact is configured, asks
 * "Send emergency alert to X?" before ever sending anything. Nothing here
 * sends silently - every SMS traces back to an explicit Yes tap.
 */
class RedAlertActivity : AppCompatActivity() {

    private var currentValue = 0
    private var currentRate: Double? = null

    private val requestSmsPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            sendToEligibleContacts()
        } else {
            Toast.makeText(this, getString(R.string.emergency_sms_denied), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_red_alert)

        findViewById<Button>(R.id.dismissButton).setOnClickListener {
            // Cancelling the notification here (not just finishing) is what
            // stops any still-playing alert audio tied to it.
            NotificationManagerCompat.from(this).cancel(AlertNotifier.RED_ALERT_NOTIFICATION_ID)
            finish()
        }

        findViewById<Button>(R.id.emergencyButton).setOnClickListener { onEmergencyButtonTapped() }

        bind(intent)
        refreshEmergencyButtonVisibility()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bind(intent)
        refreshEmergencyButtonVisibility()
    }

    private fun bind(intent: Intent) {
        val value = intent.getIntExtra(EXTRA_VALUE, 0)
        val projected = if (intent.hasExtra(EXTRA_PROJECTED)) intent.getIntExtra(EXTRA_PROJECTED, 0) else null
        val rate = if (intent.hasExtra(EXTRA_RATE)) intent.getDoubleExtra(EXTRA_RATE, 0.0) else null
        currentValue = value
        currentRate = rate
        val arrow = GlucoseTrendArrow.fromRatePerMinute(rate)

        findViewById<TextView>(R.id.alertValueText).text = "$value"
        findViewById<TextView>(R.id.alertTrendText).text = when {
            rate == null -> arrow.label
            rate > 0 -> "${arrow.label}  +${"%.1f".format(rate)} mg/dL/min"
            else -> "${arrow.label}  ${"%.1f".format(rate)} mg/dL/min"
        }
        findViewById<TextView>(R.id.alertProjectedText).text =
            if (projected != null) getString(R.string.red_alert_projected, projected)
            else getString(R.string.red_alert_no_projection)
    }

    /** Red fires for both a critically low and a critically high reading -
     *  the same 70 mg/dL split the chart's low/high limit lines use. */
    private fun alertType(): EmergencyAlertType =
        if (currentValue <= LOW_HIGH_SPLIT) EmergencyAlertType.LOW else EmergencyAlertType.HIGH

    private fun refreshEmergencyButtonVisibility() {
        val button = findViewById<Button>(R.id.emergencyButton)
        if (!EmergencyContactsPrefs.isEnabled(this)) {
            button.visibility = android.view.View.GONE
            return
        }
        lifecycleScope.launch {
            val contacts = EmergencyAlertRepository.contacts(this@RedAlertActivity).first()
            button.visibility = if (contacts.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    private fun onEmergencyButtonTapped() {
        lifecycleScope.launch {
            val allContacts = EmergencyAlertRepository.contacts(this@RedAlertActivity).first()
            val eligible = EmergencyAlertRepository.eligibleContacts(this@RedAlertActivity, allContacts)
            if (eligible.isEmpty()) {
                // Everyone configured was already messaged within the cooldown
                // window - re-showing the confirmation dialog here would just
                // be the spam this cooldown exists to prevent.
                Toast.makeText(
                    this@RedAlertActivity,
                    "Already sent recently - waiting out the cooldown",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            showConfirmationDialog(eligible)
        }
    }

    private fun showConfirmationDialog(eligible: List<EmergencyContact>) {
        val names = eligible.joinToString(", ") { it.name }
        AlertDialog.Builder(this)
            .setTitle("Send emergency alert to $names?")
            .setPositiveButton("Yes") { _, _ -> onConfirmedSend() }
            .setNegativeButton("No", null)
            .show()
    }

    /** Called after the user taps Yes. Checks/requests SEND_SMS first (with
     *  an in-app rationale shown before the system dialog, since this is a
     *  sensitive permission) - the actual send only happens once it's
     *  confirmed granted, either here or from the permission callback. */
    private fun onConfirmedSend() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            sendToEligibleContacts()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("SMS permission needed")
            .setMessage(R.string.emergency_sms_rationale)
            .setPositiveButton("Continue") { _, _ -> requestSmsPermission.launch(Manifest.permission.SEND_SMS) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendToEligibleContacts() {
        lifecycleScope.launch {
            val allContacts = EmergencyAlertRepository.contacts(this@RedAlertActivity).first()
            val eligible = EmergencyAlertRepository.eligibleContacts(this@RedAlertActivity, allContacts)
            var sentCount = 0
            for (contact in eligible) {
                runCatching {
                    EmergencyAlertRepository.send(this@RedAlertActivity, contact, alertType())
                    sentCount++
                }.onFailure {
                    Toast.makeText(this@RedAlertActivity, getString(R.string.emergency_send_failed), Toast.LENGTH_LONG).show()
                }
            }
            if (sentCount > 0) {
                Toast.makeText(this@RedAlertActivity, "Emergency alert sent to $sentCount contact(s)", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val EXTRA_VALUE = "value"
        private const val EXTRA_PROJECTED = "projected"
        private const val EXTRA_RATE = "rate"
        private const val LOW_HIGH_SPLIT = 70

        fun createIntent(context: Context, value: Int, projected: Int?, rate: Double?): Intent =
            Intent(context, RedAlertActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_VALUE, value)
                .apply {
                    projected?.let { putExtra(EXTRA_PROJECTED, it) }
                    rate?.let { putExtra(EXTRA_RATE, it) }
                }
    }
}
