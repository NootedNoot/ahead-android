package com.aheadt1d.app.alerts

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import com.aheadt1d.app.MainActivity
import com.aheadt1d.app.emergency.EmergencyAlertRepository
import com.aheadt1d.app.emergency.EmergencyAlertType
import com.aheadt1d.app.emergency.EmergencyContactsPrefs
import com.aheadt1d.app.notifications.NotificationIconFactory
import com.aheadt1d.app.state.LatestTrendRepository
import com.aheadt1d.app.voice.VoiceAlertCategory
import com.aheadt1d.app.voice.VoiceAlertEngine

/**
 * A critical-low emergency siren, deliberately independent of AlertCoordinator's
 * red-alert machinery - see CriticalLowMath's doc for why. Built after a real
 * incident: the normal red alert's full-screen takeover fired correctly, but
 * its sound/vibration didn't, because notification-channel-mediated audio
 * depends on a fragile OS permission (notification policy / DND-bypass
 * access) that's known to get silently revoked by OEM battery management.
 * This fires sound and vibration DIRECTLY instead of through a notification
 * channel - alarm-stream audio and raw Vibrator calls are exempt from DND in
 * every mode except "Total Silence", with no special permission required.
 *
 * Loops continuously (looping ringtone + looping vibration, both re-asserted
 * every [TICK_INTERVAL_MS] via an AlarmManager.setAlarmClock() chain - the
 * strongest Doze/battery exemption Android has, so the loop restarts itself
 * even if the process holding the original Ringtone/Vibrator objects was
 * killed mid-emergency) until the user dismisses it from RedAlertActivity or
 * a later reading shows real recovery (CriticalLowMath.hasRecovered). Capped
 * at [MAX_TICKS] as a hard backstop against a runaway loop if something
 * breaks - better to eventually go quiet than to drain the battery to zero
 * forever.
 *
 * Dismissing does NOT mean "this glucose value is fine now" - it almost
 * always still IS critical the moment it's dismissed (that's the whole
 * point: the person just started treating it). [check] therefore tracks a
 * separate "acknowledged" bit alongside "active": stop() sets it, so the
 * very next check cycle - which will see the same still-critical value,
 * since dismissing a notification doesn't change actual blood sugar - does
 * NOT read that as a brand-new episode and restart the whole siren. It's
 * only cleared once a reading actually recovers (CriticalLowMath.hasRecovered),
 * at which point a later drop is unambiguously a fresh episode again.
 * Found live: without this, dismissing only silenced the CURRENT loop
 * instance - the next ~60s render cycle saw "still critical, not active"
 * and started an entirely new one, so dismissing appeared to do nothing.
 */
object CriticalLowSiren {
    private const val TAG = "CriticalLowSiren"
    private const val PREFS_NAME = "ahead_critical_low_siren"
    private const val KEY_ACTIVE = "active"
    private const val KEY_ACKNOWLEDGED = "acknowledged"
    private const val KEY_VALUE = "value"
    private const val KEY_TICK_COUNT = "tick_count"
    private const val KEY_LAST_TICK_AT = "last_tick_at_ms"

    const val CHANNEL_ID = "glucose_critical_low_emergency"
    private const val NOTIFICATION_ID = 2005 // distinct from AlertNotifier's 2001-2004
    private const val REQUEST_CODE = 4713 // distinct from AlarmScheduler(4711)/EmergencyAlertScheduler(4712)

    private const val TICK_INTERVAL_MS = 25_000L

    // Deliberately its own, tighter constant - NOT
    // EmergencyContactsPrefs.alertTimeoutMinutes() (the general red-alert
    // default, 15 min) - a value under the critical floor is more severe
    // than an ordinary red alert and earns a faster escalation to a human.
    const val EMERGENCY_CONTACT_TIMEOUT_MINUTES = 10L

    // How long without a real tick before maybeStart treats the loop as dead
    // and resurrects it, rather than trusting KEY_ACTIVE blindly. Found via
    // live device testing (2026-07-31): an app reinstall/update cancels the
    // pending AlarmManager chain (documented OS behavior) without ever
    // clearing KEY_ACTIVE, which left the siren stuck "active" but silent
    // with no way to recover on its own. GlucoseStatusService's render loop
    // already calls maybeStart every ~60s regardless of whether the siren is
    // active, so this makes that call double as a heartbeat check - a real
    // (non-reinstall) process kill mid-emergency now self-heals within one
    // render cycle instead of staying silently stuck. Set comfortably above
    // both the 25s requested tick interval and observed ~60s real-world
    // cadence on at least one OEM (setAlarmClock isn't always exact) so
    // healthy operation never falsely triggers a redundant re-fire.
    private const val HEARTBEAT_STALL_MS = 90_000L

    // 240 * 25s = 100 minutes - generous, but finite. An emergency this long
    // unresolved and unacknowledged is already far outside anything the app
    // can meaningfully help with further; this exists purely so a bug can
    // never turn into an indefinite battery drain.
    private const val MAX_TICKS = 240

    private val VIBRATION_PATTERN = longArrayOf(0, 800, 400, 800, 400, 1200, 600)

    // In-memory only - naturally lost on process death, which is fine: tick()
    // re-asserts sound/vibration unconditionally every cycle regardless of
    // what this holds, so a fresh process just starts a fresh Ringtone.
    private var ringtone: Ringtone? = null

    /** Entry point - called every check cycle alongside (never instead of)
     *  AlertCoordinator.evaluate(), from GlucoseStatusService. Reconciles all
     *  three states in one call rather than only ever starting:
     *   - not critical, but still marked active -> the tick loop's own
     *     recovery check never got to run (e.g. the chain was dead) - clean
     *     it up here instead of leaving a stale active flag sitting around.
     *   - critical, not yet active -> start a fresh loop.
     *   - critical, already active -> doubles as a heartbeat check (see
     *     HEARTBEAT_STALL_MS) rather than trusting KEY_ACTIVE blindly - a
     *     repeat call must not restart the pattern from scratch while it's
     *     genuinely still looping, but must resurrect it if the tick chain
     *     has actually gone quiet (found via live testing: an app
     *     reinstall/update cancels pending AlarmManager alarms without
     *     clearing KEY_ACTIVE). */
    fun check(context: Context, value: Int, floor: Int = CriticalLowMath.DEFAULT_FLOOR) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentlyActive = prefs.getBoolean(KEY_ACTIVE, false)

        if (!CriticalLowMath.isCriticalLow(value, floor)) {
            if (currentlyActive) {
                Log.i(TAG, "value no longer critical ($value) - stopping siren")
                stop(appContext)
            } else if (prefs.getBoolean(KEY_ACKNOWLEDGED, false)) {
                Log.i(TAG, "value no longer critical ($value) - clearing acknowledged episode")
                prefs.edit { putBoolean(KEY_ACKNOWLEDGED, false) }
            }
            return
        }

        if (!currentlyActive) {
            if (prefs.getBoolean(KEY_ACKNOWLEDGED, false)) {
                // Same episode the user already dismissed - still critical
                // (dismissing a notification doesn't change actual blood
                // sugar), but they've already seen it. Stay quiet until
                // either a real recovery clears the flag above, or a fresh
                // start() call happens some other way.
                return
            }
            Log.w(TAG, "critical low ($value <= $floor) - starting emergency siren")
            start(appContext, value)
            return
        }

        val lastTickAt = prefs.getLong(KEY_LAST_TICK_AT, 0L)
        if (System.currentTimeMillis() - lastTickAt > HEARTBEAT_STALL_MS) {
            Log.w(TAG, "siren marked active but no tick in over ${HEARTBEAT_STALL_MS / 1000}s - resurrecting")
            stampTick(appContext, prefs)
            fireTick(appContext, value)
            scheduleNextTick(appContext)
        }
    }

    fun isActive(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ACTIVE, false)

    private fun start(context: Context, value: Int) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            putBoolean(KEY_ACTIVE, true)
            putBoolean(KEY_ACKNOWLEDGED, false)
            putInt(KEY_VALUE, value)
            putInt(KEY_TICK_COUNT, 0)
        }
        stampTick(appContext, prefs)
        fireTick(appContext, value)
        scheduleNextTick(appContext)
        scheduleEmergencyContactAlert(appContext, value)
    }

    private fun stampTick(context: Context, prefs: android.content.SharedPreferences) {
        prefs.edit { putLong(KEY_LAST_TICK_AT, System.currentTimeMillis()) }
    }

    /** Armed once per genuinely new episode (start() only - never on a
     *  resurrect/heartbeat re-fire, which would keep pushing the deadline
     *  out forever and defeat the point of a fixed unacknowledged-duration
     *  timeout). No-ops if the feature is off, same gate AlertCoordinator's
     *  own scheduleEmergencyAlert uses. */
    private fun scheduleEmergencyContactAlert(context: Context, value: Int) {
        if (!EmergencyContactsPrefs.isEnabled(context)) return
        val message = EmergencyAlertRepository.messageFor(
            context,
            EmergencyAlertType.LOW,
            value,
            rate = null,
            minutesUnacknowledged = EMERGENCY_CONTACT_TIMEOUT_MINUTES,
        )
        CriticalLowEmergencyScheduler.schedule(context, message, EMERGENCY_CONTACT_TIMEOUT_MINUTES)
    }

    /** Called by CriticalLowAlarmReceiver every ~25s while active. Checks for
     *  recovery/max-ticks first; otherwise re-asserts everything (sound,
     *  vibration, voice, notification) and reschedules - the re-assert is
     *  what makes this self-healing after a process death mid-emergency. */
    fun tick(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return

        val tickCount = prefs.getInt(KEY_TICK_COUNT, 0) + 1
        if (tickCount > MAX_TICKS) {
            Log.w(TAG, "hit MAX_TICKS ($MAX_TICKS) unresolved and unacknowledged - stopping as a safety backstop")
            stop(appContext)
            return
        }

        val raw = LatestTrendRepository.latestRawReading.value
        if (raw != null && CriticalLowMath.hasRecovered(raw.value)) {
            Log.i(TAG, "glucose recovered (${raw.value}) - stopping siren")
            stop(appContext)
            return
        }
        val currentValue = raw?.value ?: prefs.getInt(KEY_VALUE, 0)

        prefs.edit { putInt(KEY_TICK_COUNT, tickCount) }
        stampTick(appContext, prefs)
        fireTick(appContext, currentValue)
        scheduleNextTick(appContext)
    }

    /** Explicit user acknowledgment from RedAlertActivity, or an internal
     *  auto-stop (recovery/max-ticks). Safe to call when nothing is active.
     *  Always marks the episode acknowledged (see the class doc) - even on
     *  the recovery path, where it's immediately moot since check() clears
     *  the flag itself the moment it observes the non-critical reading that
     *  triggered this call. */
    fun stop(context: Context) {
        val appContext = context.applicationContext
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_ACTIVE, false)
            putBoolean(KEY_ACKNOWLEDGED, true)
            remove(KEY_VALUE)
            remove(KEY_TICK_COUNT)
            remove(KEY_LAST_TICK_AT)
        }
        ringtone?.let { runCatching { if (it.isPlaying) it.stop() } }
        ringtone = null
        runCatching { vibrator(appContext)?.cancel() }
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        alarmManager?.cancel(operationPendingIntent(appContext))
        NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID)
        CriticalLowEmergencyScheduler.cancel(appContext)
    }

    private fun fireTick(context: Context, value: Int) {
        ensureChannel(context)
        postNotification(context, value)
        forceAlarmVolumeAndPlaySound(context)
        vibrate(context)
        VoiceAlertEngine.speak(
            context,
            VoiceAlertCategory.EMERGENCY,
            "Emergency. Glucose is $value. This is a critical low. Treat now."
        )
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Critical low emergency",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Fires below ${CriticalLowMath.DEFAULT_FLOOR} mg/dL - independent of the normal red alert"
            setBypassDnd(true)
            enableVibration(true)
            vibrationPattern = VIBRATION_PATTERN
            // Secondary/defense-in-depth layer only - the actual repeat sound
            // comes from the directly-played Ringtone in
            // forceAlarmVolumeAndPlaySound, which doesn't depend on this
            // channel's DND-bypass actually having stuck.
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
        nm.createNotificationChannel(channel)
    }

    private fun postNotification(context: Context, value: Int) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val fullScreenIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            RedAlertActivity.createCriticalEmergencyIntent(context, value),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(NotificationIconFactory.warningIcon(context))
            .setContentTitle("🚨 CRITICAL LOW: $value mg/dL")
            .setContentText("Treat now — tap to open")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            // Can't be swiped away - only an in-app dismiss (or recovery)
            // clears this. A true emergency shouldn't be cancelable by accident.
            .setOngoing(true)
            .setContentIntent(fullScreenIntent)
            .setFullScreenIntent(fullScreenIntent, true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun forceAlarmVolumeAndPlaySound(context: Context) {
        val audioManager = context.getSystemService(AudioManager::class.java)
        if (audioManager != null) {
            runCatching {
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
            }.onFailure { Log.w(TAG, "couldn't force alarm volume", it) }
        }

        val current = ringtone
        if (current != null && runCatching { current.isPlaying }.getOrDefault(false)) return // already looping

        runCatching {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val newRingtone = RingtoneManager.getRingtone(context, uri) ?: return
            newRingtone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            newRingtone.isLooping = true
            newRingtone.play()
            ringtone = newRingtone
        }.onFailure { Log.w(TAG, "couldn't play alarm sound", it) }
    }

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private fun vibrate(context: Context) {
        val v = vibrator(context) ?: return
        runCatching {
            if (!v.hasVibrator()) return
            // repeat index 0 -> loops the whole pattern indefinitely until cancel().
            v.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, 0))
        }.onFailure { Log.w(TAG, "couldn't vibrate", it) }
    }

    private fun scheduleNextTick(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAt = System.currentTimeMillis() + TICK_INTERVAL_MS
        val showIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            // setAlarmClock, not setExactAndAllowWhileIdle: the strongest wake/
            // Doze-exemption guarantee Android offers, the same mechanism real
            // alarm-clock apps rely on - no special permission needed, and the
            // OS treats it as something it must not defer.
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showIntent), operationPendingIntent(context))
        }.onFailure { Log.w(TAG, "couldn't schedule next siren tick", it) }
    }

    private fun operationPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, CriticalLowAlarmReceiver::class.java)
            .setAction(CriticalLowAlarmReceiver.ACTION_TICK)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
