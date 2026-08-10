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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aheadt1d.app.R
import com.aheadt1d.app.emergency.EmergencyAlertRepository
import com.aheadt1d.app.emergency.EmergencyAlertType
import com.aheadt1d.app.emergency.EmergencyContact
import com.aheadt1d.app.emergency.EmergencyContactsPrefs
import com.aheadt1d.app.health.HealthConnectManager
import com.aheadt1d.app.notifications.GlucoseTrendArrow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.aheadt1d.ratemath.RateMath

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
 *
 * Three content modes, same screen: a live glucose reading ([createIntent]),
 * a signal-lost blackout ([createSignalLostIntent], see AlertNotifier's
 * showSignalLostAlert), or a critical-low siren episode
 * ([createCriticalEmergencyIntent], see CriticalLowSiren). The signal-lost
 * mode never shows a live glucose number or claims a severity - there isn't
 * one to confirm - and hides the Emergency Contact button, since
 * EmergencyAlertRepository only knows how to word a HIGH/LOW alert and
 * neither is true here.
 *
 * The dismiss button always calls CriticalLowSiren.stop() as well as the
 * normal AlertNotifier.cancelRed() - unconditionally, not just when this
 * screen was opened in emergency mode - since the siren can start
 * independently while this screen is already showing (singleTop delivers it
 * via onNewIntent), and one dismiss action should reliably silence
 * everything currently active rather than requiring the user to figure out
 * which alert is which.
 *
 * TICKET-009 (2026-08-07): the value/rate/projection this screen shows used
 * to come ONLY from the Intent extras captured at the moment the alert
 * fired, with no update for as long as the screen stayed open. The backend
 * check loop that decides severity runs on a ~5-min cadence (see
 * GlucoseStatusService), so a real rate reversal - most commonly right after
 * a treated low starts climbing - could land well before the next
 * AlertCoordinator re-fire, leaving this takeover screen visibly
 * contradicting the person's own current reading ("projected 53 in 15 min"
 * while the actual trend had already flipped positive). [startLiveRefresh]
 * now re-reads Health Connect directly on a short interval while this screen
 * is visible and updates the displayed value/rate/projection in place -
 * deliberately display-only: it does NOT touch severity, does NOT
 * re-evaluate whether the alert should have fired, and does NOT auto-dismiss
 * the screen even if the value climbs back to safe (that's still on the
 * person, same as every other recovery path in this codebase). Skipped in
 * signal-lost mode (there's no live number to refresh - see the class doc)
 * and critical-emergency mode (CriticalLowSiren already re-fires this screen
 * every ~25-60s with a fresh cached value, so a second independent refresh
 * loop here would be redundant).
 */
class RedAlertActivity : AppCompatActivity() {

    private var currentValue = 0
    private var currentRate: Double? = null
    private var currentProjected: Int? = null
    private var isSignalLostMode = false
    private var isCriticalEmergency = false

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
            // AlertNotifier.cancelRed (not just NotificationManagerCompat's
            // plain cancel) - it's also the one place a pending emergency-
            // contact auto-text timer gets torn down, so an explicit dismiss
            // here means Gma is NOT texted for this episode. Also stops any
            // still-playing alert audio tied to the notification.
            AlertNotifier.cancelRed(this)
            // Unconditional - see the class doc for why this always runs
            // regardless of which mode this screen was opened in.
            CriticalLowSiren.stop(this)
            // Starts AlertCoordinator's own dismiss cooldown so the ordinary
            // red full-screen takeover (not deep enough to trip
            // CriticalLowSiren's own acknowledgment latch) doesn't pop back
            // up minutes later for the same still-sticky low - see
            // AlertCoordinator.DISMISS_COOLDOWN_MS.
            AlertCoordinator.recordDismissal(this)
            finish()
        }

        findViewById<Button>(R.id.emergencyButton).setOnClickListener { onEmergencyButtonTapped() }

        bind(intent)
        refreshEmergencyButtonVisibility()
        startLiveRefresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bind(intent)
        refreshEmergencyButtonVisibility()
    }

    private fun bind(intent: Intent) {
        isSignalLostMode = intent.getBooleanExtra(EXTRA_SIGNAL_LOST, false)
        isCriticalEmergency = intent.getBooleanExtra(EXTRA_CRITICAL_EMERGENCY, false)
        val headingView = findViewById<TextView>(R.id.alertHeadingText)
        findViewById<Button>(R.id.dismissButton).setText(
            if (isCriticalEmergency) R.string.red_alert_stop_alarm else R.string.red_alert_dismiss
        )

        if (isSignalLostMode) {
            val lastValue = intent.getIntExtra(EXTRA_VALUE, 0)
            val ageMinutes = intent.getLongExtra(EXTRA_AGE_MINUTES, 0L)
            val arrow = intent.getStringExtra(EXTRA_ARROW)?.let {
                runCatching { GlucoseTrendArrow.valueOf(it) }.getOrNull()
            } ?: GlucoseTrendArrow.FLAT
            currentValue = lastValue
            currentRate = null
            currentProjected = null

            headingView.setText(R.string.red_alert_signal_lost_heading)
            // Deliberately NOT a glucose number here (see the class doc) - a
            // glyph in the same giant/bold/red slot the live reading normally
            // occupies, so the screen still reads as urgent at a glance
            // without implying a confirmed current value.
            findViewById<TextView>(R.id.alertValueText).text = "⚠"
            findViewById<TextView>(R.id.alertTrendText).text = "Last known: $lastValue mg/dL ${arrow.label}"
            findViewById<TextView>(R.id.alertProjectedText).text =
                "${ageMinutes}m since the last reading — check your sensor or connection now"
            return
        }

        headingView.setText(if (isCriticalEmergency) R.string.red_alert_critical_emergency_heading else R.string.red_alert_heading)
        currentValue = intent.getIntExtra(EXTRA_VALUE, 0)
        currentProjected = if (intent.hasExtra(EXTRA_PROJECTED)) intent.getIntExtra(EXTRA_PROJECTED, 0) else null
        currentRate = if (intent.hasExtra(EXTRA_RATE)) intent.getDoubleExtra(EXTRA_RATE, 0.0) else null
        renderReading()
    }

    /** Renders currentValue/currentRate/currentProjected - shared by the
     *  initial Intent-driven [bind] and [refreshLiveReading]'s periodic
     *  on-device update, so both paths draw the exact same way. */
    private fun renderReading() {
        val value = currentValue
        val rate = currentRate
        val projected = currentProjected
        val arrow = GlucoseTrendArrow.fromRatePerMinute(rate)

        findViewById<TextView>(R.id.alertValueText).text = "$value"
        findViewById<TextView>(R.id.alertTrendText).text = when {
            rate == null -> arrow.label
            rate > 0 -> "${arrow.label}  +${"%.1f".format(rate)} mg/dL/min"
            else -> "${arrow.label}  ${"%.1f".format(rate)} mg/dL/min"
        }
        findViewById<TextView>(R.id.alertProjectedText).text = when {
            isCriticalEmergency -> getString(R.string.red_alert_critical_emergency_subtext)
            projected != null -> getString(R.string.red_alert_projected, projected)
            else -> getString(R.string.red_alert_no_projection)
        }
    }

    /** See the class doc's TICKET-009 note. Re-reads Health Connect directly
     *  on a short interval while this screen is visible, so a rate reversal
     *  that lands after the alert fired (most commonly right after a treated
     *  low starts climbing) shows up here instead of leaving this takeover
     *  screen stuck on the stale value/projection that triggered it.
     *  Display-only - never touches severity or auto-dismisses. */
    private fun startLiveRefresh() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(LIVE_REFRESH_INTERVAL_MS)
                    refreshLiveReading()
                }
            }
        }
    }

    private suspend fun refreshLiveReading() {
        if (isSignalLostMode || isCriticalEmergency) return
        val points = HealthConnectManager.readGlucosePoints(this, LIVE_REFRESH_WINDOW_MINUTES)
        val latest = points.lastOrNull() ?: return
        currentValue = latest.sgv
        currentRate = HealthConnectManager.calculateRatePerMinute(points)
        currentProjected = currentRate?.let { RateMath.project(latest.sgv, it, PROJECTION_MINUTES) }
        renderReading()
    }

    /** Red fires for both a critically low and a critically high reading -
     *  the same 70 mg/dL split the chart's low/high limit lines use. Signal-
     *  lost mode overrides both: there's no confirmed high/low to report,
     *  only that the data feed itself stopped. Also checks currentProjected,
     *  not just currentValue - matching AlertCoordinator/AlertNotifier's own
     *  fix (see AlertCoordinator's isLowSide doc): a still-high current
     *  value that's projected to crash into the low band within 15 min must
     *  still count as LOW here, or a manual "notify my contact" tap from
     *  this screen would send the wrong message type. */
    private fun alertType(): EmergencyAlertType = when {
        isSignalLostMode -> EmergencyAlertType.NO_DATA
        currentValue <= LOW_HIGH_SPLIT -> EmergencyAlertType.LOW
        currentProjected != null && currentProjected!! <= LOW_HIGH_SPLIT -> EmergencyAlertType.LOW
        else -> EmergencyAlertType.HIGH
    }

    private fun refreshEmergencyButtonVisibility() {
        val button = findViewById<Button>(R.id.emergencyButton)
        // No confirmed high/low to report in signal-lost mode - see the class
        // doc. Checked before the enabled/contacts lookup below so this mode
        // never even flashes the button on before hiding it.
        if (isSignalLostMode || !EmergencyContactsPrefs.isEnabled(this)) {
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
            .setMessage(getString(R.string.emergency_sms_rationale, EmergencyContactsPrefs.alertTimeoutMinutes(this)))
            .setPositiveButton("Continue") { _, _ -> requestSmsPermission.launch(Manifest.permission.SEND_SMS) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendToEligibleContacts() {
        lifecycleScope.launch {
            val type = alertType()
            // No minutesUnacknowledged here - a manual tap is an in-the-moment
            // confirmation, not a measured wait (that framing belongs to the
            // automatic 15-min timeout - see EmergencyAlertScheduler).
            val message = EmergencyAlertRepository.messageFor(
                this@RedAlertActivity, type, currentValue, currentRate,
                minutesUnacknowledged = null, projected = currentProjected,
            )
            val allContacts = EmergencyAlertRepository.contacts(this@RedAlertActivity).first()
            val eligible = EmergencyAlertRepository.eligibleContacts(this@RedAlertActivity, allContacts)
            var sentCount = 0
            for (contact in eligible) {
                runCatching {
                    EmergencyAlertRepository.sendMessage(this@RedAlertActivity, contact, type, message)
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
        private const val EXTRA_SIGNAL_LOST = "signal_lost"
        private const val EXTRA_AGE_MINUTES = "age_minutes"
        private const val EXTRA_ARROW = "arrow"
        private const val EXTRA_CRITICAL_EMERGENCY = "critical_emergency"
        private const val LOW_HIGH_SPLIT = 70
        // See startLiveRefresh's doc (TICKET-009) - short enough that a real
        // reversal shows up well within one CGM cadence of it happening.
        private const val LIVE_REFRESH_INTERVAL_MS = 30_000L
        private const val LIVE_REFRESH_WINDOW_MINUTES = 45L
        // Matches trend-detector.js's PROJECTION_MINUTES (the backend's own
        // flat 15-min horizon). Deliberately RateMath.project (a flat
        // extrapolation), not the backend's decay-adjusted redProjected -
        // this is a live display refresh, not a severity re-evaluation, see
        // the class doc's TICKET-009 note.
        private const val PROJECTION_MINUTES = 15

        fun createIntent(context: Context, value: Int, projected: Int?, rate: Double?): Intent =
            Intent(context, RedAlertActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_VALUE, value)
                .apply {
                    projected?.let { putExtra(EXTRA_PROJECTED, it) }
                    rate?.let { putExtra(EXTRA_RATE, it) }
                }

        /** The signal-lost variant of the same takeover screen - see the
         *  class doc. [lastArrow] rides as its enum name (a plain String
         *  extra) rather than needing GlucoseTrendArrow to be Parcelable. */
        fun createSignalLostIntent(context: Context, lastValue: Int, lastArrow: GlucoseTrendArrow, ageMinutes: Long): Intent =
            Intent(context, RedAlertActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_SIGNAL_LOST, true)
                .putExtra(EXTRA_VALUE, lastValue)
                .putExtra(EXTRA_ARROW, lastArrow.name)
                .putExtra(EXTRA_AGE_MINUTES, ageMinutes)

        /** The critical-low siren variant of the same takeover screen - see
         *  the class doc and CriticalLowSiren. No projection/rate shown (the
         *  siren re-fires every ~25s off whatever the latest cached reading
         *  is, not a fresh backend projection) - just the value and the
         *  "alarm repeats until you confirm" subtext. */
        fun createCriticalEmergencyIntent(context: Context, value: Int): Intent =
            Intent(context, RedAlertActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_CRITICAL_EMERGENCY, true)
                .putExtra(EXTRA_VALUE, value)
    }
}
