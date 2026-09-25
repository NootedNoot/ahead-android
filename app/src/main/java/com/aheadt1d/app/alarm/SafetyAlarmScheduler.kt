package com.aheadt1d.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Schedules high-priority Safety Wake-Up Alarms.
 * Uses AlarmManager.setAlarmClock() which is exempt from Doze/battery-optimisation
 * and reliably wakes the phone up even in deep sleep or silent mode.
 */
object SafetyAlarmScheduler {
    private const val TAG = "SafetyAlarmScheduler"
    private const val REQUEST_CODE = 8821

    fun schedule(context: Context, minutesFromNow: Int, customMessage: String, presetLabel: String) {
        val triggerAtMillis = System.currentTimeMillis() + (minutesFromNow.toLong() * 60_000L)
        scheduleExact(context, triggerAtMillis, customMessage, presetLabel)
    }

    fun scheduleExact(context: Context, triggerAtMillis: Long, customMessage: String, presetLabel: String) {
        val appContext = context.applicationContext
        SafetyAlarmPrefs.arm(appContext, triggerAtMillis, customMessage, presetLabel)

        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val fireIntent = Intent(appContext, SafetyAlarmReceiver::class.java).apply {
            action = SafetyAlarmReceiver.ACTION_FIRE
            putExtra(SafetyAlarmReceiver.EXTRA_MESSAGE, customMessage)
            putExtra(SafetyAlarmReceiver.EXTRA_LABEL, presetLabel)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getBroadcast(appContext, REQUEST_CODE, fireIntent, flags)

        // Show intent opens MainActivity when the user taps the status bar alarm icon
        val showIntent = Intent(appContext, com.aheadt1d.app.MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val showPendingIntent = PendingIntent.getActivity(appContext, REQUEST_CODE + 1, showIntent, flags)

        try {
            val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerAtMillis, showPendingIntent)
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
            Log.d(TAG, "Safety Alarm scheduled via setAlarmClock for timestamp $triggerAtMillis")
        } catch (e: Exception) {
            Log.w(TAG, "setAlarmClock failed, falling back to setExactAndAllowWhileIdle: ${e.message}")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to schedule alarm: ${ex.message}")
            }
        }
    }

    fun cancel(context: Context) {
        val appContext = context.applicationContext
        SafetyAlarmPrefs.disarm(appContext)

        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(appContext, SafetyAlarmReceiver::class.java).apply {
            action = SafetyAlarmReceiver.ACTION_FIRE
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getBroadcast(appContext, REQUEST_CODE, intent, flags)
        alarmManager.cancel(pendingIntent)

        // Also stop active ringing if currently sounding
        SafetyAlarmRingingService.stopRinging(appContext)
        Log.d(TAG, "Safety Alarm cancelled")
    }

    fun snooze(context: Context, snoozeMinutes: Int = 10) {
        val msg = SafetyAlarmPrefs.getCustomMessage(context)
        val label = SafetyAlarmPrefs.getPresetLabel(context) + " (Snoozed)"
        SafetyAlarmRingingService.stopRinging(context)
        schedule(context, snoozeMinutes, msg, label)
    }

    fun rescheduleIfArmed(context: Context) {
        val appContext = context.applicationContext
        if (!SafetyAlarmPrefs.isArmed(appContext)) return
        val trigger = SafetyAlarmPrefs.getTriggerTime(appContext)
        if (trigger > System.currentTimeMillis()) {
            val msg = SafetyAlarmPrefs.getCustomMessage(appContext)
            val label = SafetyAlarmPrefs.getPresetLabel(appContext)
            scheduleExact(appContext, trigger, msg, label)
        } else {
            SafetyAlarmPrefs.disarm(appContext)
        }
    }
}
