package com.aheadt1d.app.emergency

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit

/** What's currently armed - the exact content the receiver should send if
 *  the timer isn't cancelled first. */
data class PendingEmergencyAlert(val type: EmergencyAlertType, val message: String)

/**
 * Doze-resilient one-shot timer for the emergency-contact auto-text.
 * [schedule] is called the moment a red-severity alert genuinely fires as a
 * NEW episode (see AlertCoordinator - never on the existing heartbeat re-
 * alert path for an already-ongoing episode, or the clock would keep getting
 * reset every ~15 minutes and a real sustained crisis would never actually
 * reach Gma). If the alert is dismissed in-app or the app itself determines
 * the condition resolved before the timer fires, [cancel] tears it back down
 * (see AlertNotifier.cancelRed/cancelAlerts, the two places every red episode
 * ends). If neither happens, EmergencyAlertReceiver fires and texts every
 * eligible emergency contact the exact pre-built message.
 *
 * Mirrors AlarmScheduler's mechanism exactly - the same setExactAndAllowWhileIdle
 * + inexact-fallback pattern already proven for the glucose-check watchdog,
 * since the task this exists for is at least as safety-critical as that one.
 *
 * The message (and its type, for the local send log) is persisted rather
 * than recomputed at fire time, so the text sent [timeoutMinutes] from now
 * always describes THIS alert's actual numbers - not whatever the app's live
 * state happens to say by the time the alarm fires, which could have moved
 * on to a different, unrelated event by then.
 */
object EmergencyAlertScheduler {
    private const val TAG = "EmergencyAlertScheduler"
    private const val PREFS_NAME = "ahead_emergency_alert_timer"
    private const val KEY_PENDING_TYPE = "pending_type"
    private const val KEY_PENDING_MESSAGE = "pending_message"
    private const val REQUEST_CODE = 4712 // distinct from AlarmScheduler's watchdog request code

    /**
     * [timeoutMinutes] is the caller's already-read EmergencyContactsPrefs.
     * alertTimeoutMinutes() value, passed through rather than re-read here -
     * the caller uses the exact same number to build [message]'s "no
     * response in X min" text, so both must come from one single read, not
     * two, or a setting change landing between them could make the message
     * and the actual delay disagree.
     */
    fun schedule(context: Context, type: EmergencyAlertType, message: String, timeoutMinutes: Long) {
        val appContext = context.applicationContext
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_PENDING_TYPE, type.storageValue)
            putString(KEY_PENDING_MESSAGE, message)
        }

        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAt = SystemClock.elapsedRealtime() + timeoutMinutes * 60_000L
        val pi = pendingIntent(appContext)
        try {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                Log.d(TAG, "armed emergency-alert timer for ${timeoutMinutes}m ($type)")
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                Log.w(TAG, "exact alarms not permitted - armed inexact emergency-alert timer")
            }
        } catch (e: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            Log.w(TAG, "exact alarm denied at set() - fell back to inexact", e)
        }
    }

    /** Tears down a pending timer - called whenever the app itself determines
     *  the red episode is no longer active (dismissed, auto-resolved,
     *  downgraded). Safe to call when nothing is pending. */
    fun cancel(context: Context) {
        val appContext = context.applicationContext
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            remove(KEY_PENDING_TYPE)
            remove(KEY_PENDING_MESSAGE)
        }
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(pendingIntent(appContext))
    }

    /**
     * Called by EmergencyAlertReceiver when the timer actually fires. Null
     * means it was already cancelled (dismissed/resolved) between the alarm
     * being queued and delivered - AlarmManager has no atomic cancel-vs-fire
     * guarantee, so the receiver must re-check this rather than assuming
     * "the alarm fired" still means "still relevant". Clears the pending
     * state either way so a stray duplicate delivery can't double-send.
     */
    fun takePending(context: Context): PendingEmergencyAlert? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val typeStr = prefs.getString(KEY_PENDING_TYPE, null)
        val message = prefs.getString(KEY_PENDING_MESSAGE, null)
        prefs.edit {
            remove(KEY_PENDING_TYPE)
            remove(KEY_PENDING_MESSAGE)
        }
        if (typeStr == null || message == null) return null
        return PendingEmergencyAlert(EmergencyAlertType.fromStorageValue(typeStr), message)
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, EmergencyAlertReceiver::class.java)
            .setAction(EmergencyAlertReceiver.ACTION_EMERGENCY_ALERT_TIMEOUT)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}
