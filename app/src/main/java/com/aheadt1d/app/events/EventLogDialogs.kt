package com.aheadt1d.app.events

import android.content.Context
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import com.aheadt1d.app.R
import com.aheadt1d.app.alerts.PlateauCoordinator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * The quick-log FAB's two entry points: a one-tap preset picker (default) and
 * a custom-note dialog (long-press). Both just call UserEventRepository.log
 * and toast a confirmation - no navigation, no dosing logic, nothing else
 * reads the tap.
 *
 * Both also accept an optional [LoggedPointContext] for backdating: when a
 * caller (GraphActivity's long-press-on-a-chart-point flow) supplies one, the
 * dialog title shows that point's value/time instead of "now", and the
 * logged event is stamped with that historical timestamp/value rather than
 * the current moment.
 */
object EventLogDialogs {

    /** A specific chart point being logged against, instead of "now". */
    data class LoggedPointContext(val timestamp: Long, val glucoseValue: Float)

    private val pointTimeFormatter = DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault())

    private fun titleFor(base: String, pointContext: LoggedPointContext?): String =
        if (pointContext == null) {
            base
        } else {
            val time = pointTimeFormatter.format(Instant.ofEpochMilli(pointContext.timestamp))
            "$base · ${pointContext.glucoseValue.toInt()} mg/dL · $time"
        }

    fun showPresetPicker(context: Context, scope: LifecycleCoroutineScope, pointContext: LoggedPointContext? = null) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 12), dp(context, 20), dp(context, 4))
        }

        lateinit var dialog: AlertDialog

        for (tag in EventTag.entries) {
            val button = Button(context).apply {
                text = "${tag.glyph}  ${tag.label}"
                isAllCaps = false
                setBackgroundResource(R.drawable.time_btn_inactive)
                setOnClickListener {
                    logAndConfirm(context, scope, tag, note = null, pointContext = pointContext)
                    dialog.dismiss()
                }
            }
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.topMargin = dp(context, 6)
            container.addView(button, params)
        }

        val customButton = Button(context).apply {
            text = "Custom note..."
            isAllCaps = false
            setBackgroundResource(R.drawable.button_background)
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                dialog.dismiss()
                showCustomNoteDialog(context, scope, pointContext)
            }
        }
        val customParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        customParams.topMargin = dp(context, 16)
        container.addView(customButton, customParams)

        dialog = AlertDialog.Builder(context)
            .setTitle(titleFor("Log an event", pointContext))
            .setView(container)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }

    fun showCustomNoteDialog(context: Context, scope: LifecycleCoroutineScope, pointContext: LoggedPointContext? = null) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_custom_event, null)
        val tagSpinner = view.findViewById<Spinner>(R.id.customEventTagSpinner)
        val noteInput = view.findViewById<EditText>(R.id.customEventNoteInput)

        tagSpinner.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            EventTag.entries.map { "${it.glyph} ${it.label}" }
        )
        // Default to OTHER, not the first preset (Stress) - this dialog is
        // reached specifically to log something the presets don't cover, so
        // leaving the spinner untouched should never silently mislabel the
        // event as Stress.
        tagSpinner.setSelection(EventTag.entries.indexOf(EventTag.OTHER))

        AlertDialog.Builder(context)
            .setTitle(titleFor("Log a custom event", pointContext))
            .setView(view)
            .setPositiveButton("Log") { _, _ ->
                val tag = EventTag.entries[tagSpinner.selectedItemPosition]
                logAndConfirm(context, scope, tag, note = noteInput.text?.toString(), pointContext = pointContext)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun logAndConfirm(
        context: Context,
        scope: LifecycleCoroutineScope,
        tag: EventTag,
        note: String?,
        pointContext: LoggedPointContext?
    ) {
        val timestamp = pointContext?.timestamp ?: System.currentTimeMillis()
        scope.launch {
            UserEventRepository.log(
                context,
                tag,
                note,
                timestamp = timestamp,
                glucoseOverride = pointContext?.glucoseValue
            )
            // Narrow, explicit exception to UserEvent's "pure logging" rule -
            // this is the one tag that opens a correction-response tracking
            // window (see PlateauCoordinator.onCorrectionLogged). Every other
            // tag is untouched by this and still feeds nothing alert-adjacent.
            if (tag == EventTag.CORRECTION) {
                PlateauCoordinator.onCorrectionLogged(context, timestamp)
            }
            Toast.makeText(context, "Logged: ${tag.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
