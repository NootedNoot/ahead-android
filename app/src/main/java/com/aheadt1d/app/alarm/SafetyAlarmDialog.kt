package com.aheadt1d.app.alarm

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.aheadt1d.app.R

/**
 * Controller for the Safety & Wake Alarm configuration dialog.
 */
object SafetyAlarmDialog {

    fun show(context: Context, onAlarmStateChanged: () -> Unit = {}) {
        val dialog = Dialog(context, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_safety_alarm, null)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        var selectedDurationMinutes = 120 // Default 2 hours
        var selectedPresetLabel = "Alcohol Check"

        val armedCard = view.findViewById<View>(R.id.alarmArmedStatusCard)
        val tvActiveCountdown = view.findViewById<TextView>(R.id.tvActiveCountdown)
        val tvActiveMsgPreview = view.findViewById<TextView>(R.id.tvActiveMsgPreview)
        val btnCancelActive = view.findViewById<Button>(R.id.btnCancelActiveAlarm)
        val btnAdd15mActive = view.findViewById<Button>(R.id.btnAdd15mActiveAlarm)

        val configContainer = view.findViewById<View>(R.id.alarmConfigContainer)
        val tvDuration = view.findViewById<TextView>(R.id.tvDurationDisplay)
        val btnMinus = view.findViewById<TextView>(R.id.btnTimeMinus15)
        val btnPlus = view.findViewById<TextView>(R.id.btnTimePlus15)
        val etMessage = view.findViewById<EditText>(R.id.etCustomTtsMessage)
        val btnArm = view.findViewById<Button>(R.id.btnArmSafetyAlarm)

        fun updateArmedState() {
            if (SafetyAlarmPrefs.isArmed(context)) {
                armedCard.visibility = View.VISIBLE
                val label = SafetyAlarmPrefs.getPresetLabel(context)
                val remaining = SafetyAlarmPrefs.getRemainingFormatted(context)
                tvActiveCountdown.text = "$label • $remaining"
                val customMsg = SafetyAlarmPrefs.getCustomMessage(context)
                tvActiveMsgPreview.text = if (customMsg.isNotBlank()) "Voice: \"$customMsg\"" else "Voice: Default wake-up call"
            } else {
                armedCard.visibility = View.GONE
            }
        }

        fun updateDurationDisplay() {
            val hours = selectedDurationMinutes / 60
            val mins = selectedDurationMinutes % 60
            tvDuration.text = if (hours > 0 && mins > 0) {
                "$hours h $mins min ($selectedDurationMinutes min)"
            } else if (hours > 0) {
                "$hours Hours ($selectedDurationMinutes min)"
            } else {
                "$mins Minutes"
            }
        }

        // Set initial state
        updateArmedState()
        updateDurationDisplay()
        etMessage.setText("Time to wake up and check blood sugar after drinks!")

        // Preset Chips
        view.findViewById<Button>(R.id.chipAlcohol).setOnClickListener {
            selectedDurationMinutes = 120
            selectedPresetLabel = "Alcohol Check"
            updateDurationDisplay()
            etMessage.setText("Time to wake up and check blood sugar after drinks!")
        }

        view.findViewById<Button>(R.id.chipAllergy).setOnClickListener {
            selectedDurationMinutes = 180
            selectedPresetLabel = "Allergy Pill Check"
            updateDurationDisplay()
            etMessage.setText("Wake up and check blood sugar after allergy pill!")
        }

        view.findViewById<Button>(R.id.chipLowCheck).setOnClickListener {
            selectedDurationMinutes = 15
            selectedPresetLabel = "Treated Low Check"
            updateDurationDisplay()
            etMessage.setText("15 minutes up! Check your blood sugar after low treatment.")
        }

        view.findViewById<Button>(R.id.chipPostMeal).setOnClickListener {
            selectedDurationMinutes = 60
            selectedPresetLabel = "Post-Meal Check"
            updateDurationDisplay()
            etMessage.setText("Post-meal check: observe insulin curve.")
        }

        // Time +/-
        btnMinus.setOnClickListener {
            if (selectedDurationMinutes > 15) {
                selectedDurationMinutes -= 15
                updateDurationDisplay()
            }
        }

        btnPlus.setOnClickListener {
            if (selectedDurationMinutes < 720) { // Up to 12 hours
                selectedDurationMinutes += 15
                updateDurationDisplay()
            }
        }

        // Arm Button
        btnArm.setOnClickListener {
            val message = etMessage.text.toString().trim()
            SafetyAlarmScheduler.schedule(context, selectedDurationMinutes, message, selectedPresetLabel)
            Toast.makeText(context, "⏰ Wake Alarm Armed for in $selectedDurationMinutes min", Toast.LENGTH_LONG).show()
            onAlarmStateChanged()
            dialog.dismiss()
        }

        // Active Alarm Controls
        btnCancelActive.setOnClickListener {
            SafetyAlarmScheduler.cancel(context)
            Toast.makeText(context, "Safety Alarm cancelled", Toast.LENGTH_SHORT).show()
            updateArmedState()
            onAlarmStateChanged()
        }

        btnAdd15mActive.setOnClickListener {
            val currentTrigger = SafetyAlarmPrefs.getTriggerTime(context)
            val newTrigger = (if (currentTrigger > System.currentTimeMillis()) currentTrigger else System.currentTimeMillis()) + 15 * 60_000L
            val msg = SafetyAlarmPrefs.getCustomMessage(context)
            val label = SafetyAlarmPrefs.getPresetLabel(context)
            SafetyAlarmScheduler.scheduleExact(context, newTrigger, msg, label)
            Toast.makeText(context, "Added 15 minutes to alarm", Toast.LENGTH_SHORT).show()
            updateArmedState()
            onAlarmStateChanged()
        }

        view.findViewById<View>(R.id.btnDialogClose).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }
}
