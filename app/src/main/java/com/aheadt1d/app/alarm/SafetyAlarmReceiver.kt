package com.aheadt1d.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * BroadcastReceiver triggered when a Safety Wake-Up Alarm fires or when
 * notification actions (Dismiss, Snooze) are clicked.
 */
class SafetyAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "onReceive action=$action")

        when (action) {
            ACTION_FIRE -> {
                val message = intent.getStringExtra(EXTRA_MESSAGE) ?: SafetyAlarmPrefs.getCustomMessage(context)
                val label = intent.getStringExtra(EXTRA_LABEL) ?: SafetyAlarmPrefs.getPresetLabel(context)
                SafetyAlarmPrefs.setRinging(context, true)

                // 1. Start foreground ringing service
                val serviceIntent = Intent(context, SafetyAlarmRingingService::class.java).apply {
                    putExtra(EXTRA_MESSAGE, message)
                    putExtra(EXTRA_LABEL, label)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }

                // 2. Launch full-screen wake-up alarm Activity
                val activityIntent = Intent(context, SafetyAlarmActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    putExtra(EXTRA_MESSAGE, message)
                    putExtra(EXTRA_LABEL, label)
                }
                context.startActivity(activityIntent)
            }

            ACTION_DISMISS -> {
                Log.d(TAG, "User dismissed Safety Alarm")
                SafetyAlarmRingingService.stopRinging(context)
                SafetyAlarmPrefs.disarm(context)
            }

            ACTION_SNOOZE -> {
                Log.d(TAG, "User snoozed Safety Alarm")
                SafetyAlarmScheduler.snooze(context, 10)
            }
        }
    }

    companion object {
        private const val TAG = "SafetyAlarmReceiver"
        const val ACTION_FIRE = "com.aheadt1d.app.alarm.ACTION_SAFETY_ALARM_FIRE"
        const val ACTION_DISMISS = "com.aheadt1d.app.alarm.ACTION_SAFETY_ALARM_DISMISS"
        const val ACTION_SNOOZE = "com.aheadt1d.app.alarm.ACTION_SAFETY_ALARM_SNOOZE"

        const val EXTRA_MESSAGE = "extra_alarm_message"
        const val EXTRA_LABEL = "extra_alarm_label"
    }
}
