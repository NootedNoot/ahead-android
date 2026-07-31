package com.aheadt1d.app.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Exact-alarm watchdog for GlucoseStatusService - the tightest layer of the
 * monitor's resilience stack.
 *
 * The other layers each have a gap this one closes:
 *  - START_STICKY only helps if the OS chooses to recreate the process.
 *  - The WorkManager periodic watchdog is subject to Doze batching and can slip
 *    well past its ~15-min period when the device sits idle overnight - exactly
 *    the window (screen locked, backgrounded) the acceptance test cares about.
 * setExactAndAllowWhileIdle fires even in Doze, giving a hard ~10-min heartbeat
 * that re-ensures the service is alive on aggressive OEM battery managers
 * (Samsung/Xiaomi) that kill foreground services regardless.
 *
 * These alarms are one-shot, so WatchdogAlarmReceiver reschedules the next one
 * every time it fires. Guarded by canScheduleExactAlarms() (API 31+): if exact
 * alarms aren't permitted we degrade to setAndAllowWhileIdle (inexact but still
 * Doze-piercing) rather than crashing or silently going dark. The manifest
 * declares USE_EXACT_ALARM (auto-granted, no user Settings step) so the exact
 * path is actually taken on modern devices out of the box.
 */
object AlarmScheduler {
    private const val TAG = "AlarmScheduler"
    private const val REQUEST_CODE = 4711

    // A hard 5-min heartbeat, matching the service's own check cadence. Each
    // firing nudges the service (WatchdogAlarmReceiver -> nudgeCheck): reviving
    // it if dead, and forcing an immediate check cycle if it's alive but its
    // loop timers are Doze-stalled - so in the worst case the monitor's real
    // cadence degrades to this interval rather than dying.
    // 2026-07-31: tightened from 10 to 5 min - a stalled service could
    // previously sit up to ~10-15 min behind real CGM data (the reported
    // "notification bar isn't keeping up" complaint) before this watchdog
    // caught it. Matching the primary cadence bounds worst-case staleness to
    // one missed cycle instead of two, at the cost of a somewhat more
    // frequent Doze wakeup - worth it for a glucose monitor.
    private const val INTERVAL_MS = 5 * 60_000L

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS
        val pi = pendingIntent(appContext)
        try {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                Log.d(TAG, "scheduled exact watchdog alarm in ${INTERVAL_MS / 60_000}m")
            } else {
                // Exact alarms revoked by the user - degrade to inexact but still
                // wake from Doze. The monitor keeps its heartbeat, just inside an
                // OS batching window instead of to-the-minute.
                alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                Log.w(TAG, "exact alarms not permitted - scheduled inexact watchdog alarm")
            }
        } catch (e: SecurityException) {
            // Some OEMs throw at set() despite canScheduleExactAlarms() returning
            // true. Never let watchdog scheduling crash the caller (the service).
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            Log.w(TAG, "exact alarm denied at set() - fell back to inexact", e)
        }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WatchdogAlarmReceiver::class.java)
            .setAction(WatchdogAlarmReceiver.ACTION_WATCHDOG)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}
