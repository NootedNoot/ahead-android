package com.aheadt1d.app.alerts

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit

/**
 * Doze-resilient one-shot timer for the critical-low siren's emergency-
 * contact auto-text - deliberately independent of
 * com.aheadt1d.app.emergency.EmergencyAlertScheduler (the normal red-alert
 * path's own timer), for the same reason CriticalLowSiren itself is
 * independent of AlertCoordinator: the normal red severity can resolve or
 * downgrade on its own (e.g. the backend's projection-based classifier
 * disagrees with the raw value) while the raw value is still under the
 * critical floor, and that must never silently tear down THIS timer just
 * because it shared a slot with the other one. Mirrors
 * EmergencyAlertScheduler's exact mechanism (same setExactAndAllowWhileIdle
 * + inexact-fallback pattern already proven for that timer).
 */
object CriticalLowEmergencyScheduler {
    private const val TAG = "CriticalLowEmergencyScheduler"
    private const val PREFS_NAME = "ahead_critical_low_emergency_timer"
    private const val KEY_PENDING_MESSAGE = "pending_message"

    // Distinct from AlarmScheduler(4711)/EmergencyAlertScheduler(4712)/
    // CriticalLowSiren's own tick chain(4713).
    private const val REQUEST_CODE = 4714

    /** [timeoutMinutes] is the caller's already-read
     *  EmergencyContactsPrefs.alertTimeoutMinutes() value - same reasoning as
     *  EmergencyAlertScheduler.schedule's doc for why it's passed through
     *  rather than re-read here. */
    fun schedule(context: Context, message: String, timeoutMinutes: Long) {
        val appContext = context.applicationContext
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_PENDING_MESSAGE, message)
        }

        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAt = SystemClock.elapsedRealtime() + timeoutMinutes * 60_000L
        val pi = pendingIntent(appContext)
        try {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                Log.d(TAG, "armed critical-low emergency-contact timer for ${timeoutMinutes}m")
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                Log.w(TAG, "exact alarms not permitted - armed inexact timer")
            }
        } catch (e: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            Log.w(TAG, "exact alarm denied at set() - fell back to inexact", e)
        }
    }

    /** Tears down a pending timer - called whenever CriticalLowSiren.stop()
     *  runs (acknowledged or auto-resolved). Safe to call when nothing is
     *  pending. */
    fun cancel(context: Context) {
        val appContext = context.applicationContext
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { remove(KEY_PENDING_MESSAGE) }
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(pendingIntent(appContext))
    }

    /** Null means it was already cancelled between the alarm being queued
     *  and delivered - see EmergencyAlertScheduler.takePending's doc for the
     *  same AlarmManager race this guards against. */
    fun takePendingMessage(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val message = prefs.getString(KEY_PENDING_MESSAGE, null)
        prefs.edit { remove(KEY_PENDING_MESSAGE) }
        return message
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, CriticalLowEmergencyReceiver::class.java)
            .setAction(CriticalLowEmergencyReceiver.ACTION_TIMEOUT)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}
