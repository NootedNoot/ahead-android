package com.aheadt1d.app.alerts

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.aheadt1d.app.R
import com.google.android.material.snackbar.Snackbar
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.round

/**
 * Add/edit/remove screen for [CustomThreshold]s - a plain dynamically-built
 * list (addView per row) rather than a RecyclerView, since this is
 * realistically a handful of entries, matching this codebase's general
 * preference for the simplest thing that works over a heavier Android idiom.
 *
 * This screen only writes CustomThresholdStore; it never touches
 * AlertCoordinator/SeverityEngine state, same isolation CustomThresholdCoordinator
 * itself keeps - see that class's own doc.
 */
class CustomThresholdsActivity : AppCompatActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var emptyState: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_thresholds)

        listContainer = findViewById(R.id.thresholdListContainer)
        emptyState = findViewById(R.id.emptyStateText)

        findViewById<TextView>(R.id.backButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.addThresholdButton).setOnClickListener { showAddDialog() }

        renderList()
    }

    override fun onResume() {
        super.onResume()
        // Re-render on resume too, not just onCreate - CustomThresholdCoordinator
        // updates currentlyCrossed/lastFiredAtMs in the background every check
        // cycle, so returning to this screen should reflect the latest state.
        renderList()
    }

    private fun renderList() {
        listContainer.removeAllViews()
        val thresholds = CustomThresholdStore.purgeExpired(this)
        emptyState.visibility = if (thresholds.isEmpty()) View.VISIBLE else View.GONE

        val inflater = LayoutInflater.from(this)
        thresholds.forEach { threshold ->
            val row = inflater.inflate(R.layout.row_custom_threshold, listContainer, false)
            // Dimmed whole-row treatment for a disabled threshold, matching
            // VoiceAlertsActivity.applyMasterState's precedent for showing an
            // "off" state at a glance rather than relying on a small status word alone.
            row.alpha = if (threshold.enabled) 1f else 0.5f
            row.findViewById<TextView>(R.id.rowLabel).text = threshold.displayLabel()

            val statusView = row.findViewById<TextView>(R.id.rowStatus)
            statusView.text = statusText(threshold)
            statusView.setTextColor(
                ContextCompat.getColor(
                    this,
                    when {
                        !threshold.enabled -> R.color.muted
                        threshold.currentlyCrossed && threshold.direction == CustomThreshold.Direction.FALLING -> R.color.low
                        threshold.currentlyCrossed -> R.color.high
                        else -> R.color.muted
                    }
                )
            )

            val enabledSwitch = row.findViewById<SwitchCompat>(R.id.rowEnabledSwitch)
            enabledSwitch.isChecked = threshold.enabled
            enabledSwitch.setOnCheckedChangeListener { _, checked ->
                CustomThresholdStore.setEnabled(this, threshold.id, checked)
                // Turning a currently-crossed threshold off shouldn't leave
                // its notification sitting in the tray implying it's still live.
                if (!checked) AlertNotifier.cancelCustomThreshold(this, threshold.id)
                renderList()
            }

            row.findViewById<TextView>(R.id.rowDeleteButton).setOnClickListener {
                val removed = threshold
                CustomThresholdStore.delete(this, threshold.id)
                AlertNotifier.cancelCustomThreshold(this, threshold.id)
                renderList()
                // Undo-capable: this is a safety-relevant alarm, so an
                // accidental tap on a small icon button shouldn't be an
                // unrecoverable, silent deletion.
                Snackbar.make(listContainer, "Deleted \"${removed.displayLabel()}\"", Snackbar.LENGTH_LONG)
                    .setAction("UNDO") {
                        CustomThresholdStore.add(this, removed)
                        renderList()
                    }
                    .show()
            }

            listContainer.addView(row)
        }
    }

    private fun statusText(t: CustomThreshold): String {
        val lifespan = if (t.temporary) {
            // t.expiresAtMs is always non-null for a temporary threshold (set
            // to endOfTodayMillis() at creation - see showAddDialog()) - the
            // null branch only guards a theoretical malformed/legacy entry.
            if (t.expiresAtMs != null) "expires midnight tonight" else "expires today"
        } else {
            "persistent"
        }
        val crossState = if (!t.enabled) {
            "Disabled"
        } else if (t.currentlyCrossed) {
            val agoMinutes = t.lastFiredAtMs?.let { (System.currentTimeMillis() - it) / 60_000L }
            when {
                agoMinutes == null -> "Crossed"
                agoMinutes < 1 -> "Crossed — last fired just now"
                else -> "Crossed — last fired ${agoMinutes}m ago"
            }
        } else {
            "Not crossed"
        }
        return "$crossState · $lifespan"
    }

    private fun formatTime(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("h:mm a"))

    // NOTE: freezes to ZoneId.systemDefault() at creation time; if the
    // device's timezone changes later today (e.g. mid-flight), this cutoff
    // stays anchored to the original zone's midnight rather than tracking
    // the new local midnight. Accepted limitation - not worth the added
    // complexity of re-deriving "is this still today" live on every check
    // for a purely informational "just for today" convenience feature.
    private fun endOfTodayMillis(): Long {
        val zone = ZoneId.systemDefault()
        return LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    }

    private fun showAddDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_custom_threshold, null)
        val dialog = AlertDialog.Builder(this).setView(view).create()

        val kindDirectionGroup = view.findViewById<RadioGroup>(R.id.kindDirectionGroup)
        val amountInput = view.findViewById<EditText>(R.id.amountInput)
        val labelInput = view.findViewById<EditText>(R.id.labelInput)
        val temporarySwitch = view.findViewById<SwitchCompat>(R.id.temporarySwitch)

        kindDirectionGroup.setOnCheckedChangeListener { _, checkedId ->
            amountInput.hint = if (checkedId == R.id.optionRateRising || checkedId == R.id.optionRateFalling) {
                "Rate, e.g. 5.0 (mg/dL/min)"
            } else {
                "Value, e.g. 300 (mg/dL)"
            }
        }

        view.findViewById<Button>(R.id.cancelButton).setOnClickListener { dialog.dismiss() }
        view.findViewById<Button>(R.id.saveButton).setOnClickListener {
            val amount = amountInput.text.toString().toDoubleOrNull()
            if (amount == null) {
                Toast.makeText(this, "Enter a number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val (kind, direction) = when (kindDirectionGroup.checkedRadioButtonId) {
                R.id.optionValueFalling -> CustomThreshold.Kind.VALUE to CustomThreshold.Direction.FALLING
                R.id.optionRateRising -> CustomThreshold.Kind.RATE to CustomThreshold.Direction.RISING
                R.id.optionRateFalling -> CustomThreshold.Kind.RATE to CustomThreshold.Direction.FALLING
                else -> CustomThreshold.Kind.VALUE to CustomThreshold.Direction.RISING
            }

            // A real CGM reading is always a positive, bounded mg/dL value -
            // a VALUE threshold of 0 or negative is permanently "crossed"
            // from the instant it's created (a silently dead alarm, not a
            // usable one). A RATE threshold of 0 is meaningless (everything
            // "crosses" it) and anything beyond a real CGM's plausible
            // range is very unlikely to be what the user meant to type.
            if (kind == CustomThreshold.Kind.VALUE && amount !in 40.0..400.0) {
                Toast.makeText(this, "Enter a glucose value between 40 and 400 mg/dL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (kind == CustomThreshold.Kind.RATE && abs(amount) !in 0.1..10.0) {
                Toast.makeText(this, "Enter a rate between 0.1 and 10.0 mg/dL/min", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // A rate threshold's amount should carry the same sign as its
            // direction (RISING -> positive, FALLING -> negative) regardless
            // of what the user typed, so "5.0" and "-5.0" both do the right
            // thing for a "falling" threshold - CustomThresholdMath compares
            // the raw signed rate against this amount directly. A VALUE
            // threshold is rounded to a whole number so the stored/compared
            // amount always matches what CustomThreshold.autoLabel() displays
            // (autoLabel truncates via .toInt() - typing "300.9" used to
            // display "300" while actually requiring 300.9+ to fire).
            val signedAmount = if (kind == CustomThreshold.Kind.RATE) {
                if (direction == CustomThreshold.Direction.RISING) abs(amount) else -abs(amount)
            } else {
                round(amount)
            }

            val isDuplicate = CustomThresholdStore.purgeExpired(this).any {
                it.kind == kind && it.direction == direction && it.amount == signedAmount
            }
            if (isDuplicate) {
                Toast.makeText(this, "You already have a threshold like this", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val temporary = temporarySwitch.isChecked
            val threshold = CustomThreshold(
                id = CustomThresholdStore.newId(),
                kind = kind,
                direction = direction,
                amount = signedAmount,
                label = labelInput.text.toString().trim(),
                temporary = temporary,
                expiresAtMs = if (temporary) endOfTodayMillis() else null,
            )
            CustomThresholdStore.add(this, threshold)
            renderList()
            dialog.dismiss()
            Toast.makeText(this, "Added: ${threshold.displayLabel()}", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, CustomThresholdsActivity::class.java)
    }
}
