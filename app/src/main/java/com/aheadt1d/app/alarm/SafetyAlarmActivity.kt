package com.aheadt1d.app.alarm

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.aheadt1d.app.MainActivity
import com.aheadt1d.app.R
import com.aheadt1d.app.state.LatestTrendRepository

/**
 * Full-screen wake-up alarm Activity.
 * Designed to show above the lock screen and turn the screen on immediately when the alarm triggers.
 */
class SafetyAlarmActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Wake and unlock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_safety_alarm)

        val message = intent.getStringExtra(SafetyAlarmReceiver.EXTRA_MESSAGE)
            ?: SafetyAlarmPrefs.getCustomMessage(this)
        val label = intent.getStringExtra(SafetyAlarmReceiver.EXTRA_LABEL)
            ?: SafetyAlarmPrefs.getPresetLabel(this)

        val tvPreset = findViewById<TextView>(R.id.tvAlarmPresetLabel)
        val tvMsg = findViewById<TextView>(R.id.tvAlarmCustomMessage)
        val tvGlucose = findViewById<TextView>(R.id.tvAlarmCurrentGlucose)

        tvPreset.text = label
        tvMsg.text = if (message.isNotBlank()) message else "Time to wake up and verify your blood sugar."

        // Live glucose from repository
        val raw = LatestTrendRepository.latestRawReading.value
        val trend = LatestTrendRepository.latestTrend.value
        if (raw != null) {
            val arrowStr = if (raw.ratePerMinute != null) {
                when {
                    raw.ratePerMinute <= -2.0 -> "↓↓"
                    raw.ratePerMinute <= -1.0 -> "↓"
                    raw.ratePerMinute <= -0.4 -> "↘"
                    raw.ratePerMinute >= 2.0 -> "↑↑"
                    raw.ratePerMinute >= 1.0 -> "↑"
                    raw.ratePerMinute >= 0.4 -> "↗"
                    else -> "→"
                }
            } else "→"
            tvGlucose.text = "${raw.value} mg/dL $arrowStr"
        } else if (trend != null) {
            tvGlucose.text = "${trend.currentValue} mg/dL"
        } else {
            tvGlucose.text = "Check Sensor"
        }

        // Dismiss button
        findViewById<Button>(R.id.btnAlarmDismiss).setOnClickListener {
            SafetyAlarmRingingService.stopRinging(this)
            SafetyAlarmPrefs.disarm(this)

            val mainIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(mainIntent)
            finish()
        }

        // Snooze button
        findViewById<Button>(R.id.btnAlarmSnooze).setOnClickListener {
            SafetyAlarmScheduler.snooze(this, 10)
            finish()
        }
    }

    override fun onBackPressed() {
        // Prevent accidental dismissal by back button; user must tap Dismiss or Snooze
    }
}
