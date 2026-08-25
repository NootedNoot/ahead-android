package com.aheadt1d.app.alerts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Handles the "Snooze / Silence" action tapped directly from a notification.
 */
class AlertSnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val durationMinutes = intent.getIntExtra(EXTRA_MINUTES, 15)
        Log.d(TAG, "Snoozing alerts for $durationMinutes minutes from notification action")
        AlertSilenceManager.silence(context, durationMinutes)
    }

    companion object {
        private const val TAG = "AlertSnoozeReceiver"
        const val ACTION_SNOOZE = "com.aheadt1d.app.alerts.ACTION_SNOOZE"
        const val EXTRA_MINUTES = "extra_minutes"

        fun createIntent(context: Context, minutes: Int = 15): Intent =
            Intent(context, AlertSnoozeReceiver::class.java).apply {
                action = ACTION_SNOOZE
                putExtra(EXTRA_MINUTES, minutes)
            }
    }
}
