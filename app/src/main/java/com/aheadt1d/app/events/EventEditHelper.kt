package com.aheadt1d.app.events

import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.aheadt1d.app.R
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * Editable bottom sheet for an already-logged event: change the tag, change
 * the note, or delete it outright. Writes back through UserEventRepository -
 * the same Room table (user_events) the CSV export, the chart overlay, and
 * the notes history screen all read from - so an edit here is visible
 * everywhere else immediately, not just from wherever it was opened.
 *
 * Extracted from GraphActivity (the chart's tap-icon entry point) so the
 * notes history screen (browse-all-events entry point) can reuse the exact
 * same edit/delete path instead of a second copy - there's already no age
 * restriction here, it was only ever GraphActivity's 6h event-fetch window
 * that made older events unreachable, which the history screen fixes by
 * fetching UserEventRepository.allEvents instead.
 */
object EventEditHelper {
    fun show(activity: AppCompatActivity, event: UserEvent, onSaved: () -> Unit = {}, onDeleted: () -> Unit = {}) {
        val sheetView = activity.layoutInflater.inflate(R.layout.bottom_sheet_edit_event, null)
        val infoText = sheetView.findViewById<TextView>(R.id.editEventInfoText)
        val tagSpinner = sheetView.findViewById<android.widget.Spinner>(R.id.editEventTagSpinner)
        val noteInput = sheetView.findViewById<android.widget.EditText>(R.id.editEventNoteInput)
        val deleteButton = sheetView.findViewById<Button>(R.id.deleteEventButton)
        val saveButton = sheetView.findViewById<Button>(R.id.saveEventButton)

        val time = DateTimeFormatter.ofPattern("MMM d, h:mm a")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(event.timestamp))
        infoText.text = event.glucoseAtTime?.let { "$time · ${it.toInt()} mg/dL" } ?: time

        val tags = EventTag.entries
        tagSpinner.adapter = android.widget.ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_dropdown_item,
            tags.map { "${it.glyph} ${it.label}" }
        )
        tagSpinner.setSelection(tags.indexOf(EventTag.fromStorageValue(event.tag)))
        noteInput.setText(event.note ?: "")

        val dialog = BottomSheetDialog(activity)
        dialog.setContentView(sheetView)

        saveButton.setOnClickListener {
            val selectedTag = tags[tagSpinner.selectedItemPosition]
            activity.lifecycleScope.launch {
                UserEventRepository.updateEvent(activity, event, selectedTag, noteInput.text?.toString())
                dialog.dismiss()
                onSaved()
            }
        }
        deleteButton.setOnClickListener {
            activity.lifecycleScope.launch {
                UserEventRepository.deleteEvent(activity, event)
                dialog.dismiss()
                onDeleted()
            }
        }

        dialog.show()
    }
}
