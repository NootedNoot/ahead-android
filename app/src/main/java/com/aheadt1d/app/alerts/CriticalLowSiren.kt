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
import com.aheadt1d.app.BuildConfig
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
 *
 * Two bands (2026-08-01), added after a direct ask from the person this app
 * is for: hypoglycemia unawareness means no adrenaline/sweat/shakes warn
 * them below ~55, so waiting for the hard floor to fire the ONE fully
 * reliable delivery mechanism in this app leaves a shrinking reaction
 * window.
 *
 *  - BAND_TANKING opens at CriticalLowMath.TANKING_ENTRY (73) or below while
 *    confirmed falling (see CriticalLowMath.isTanking - a flat or rising
 *    value in the same range is routine and opens nothing). Once open it
 *    pings on a descending ladder (73/70/67/63, CriticalLowMath.TANKING_RUNGS)
 *    - each rung once, downward only - plus a "still not resolved" heartbeat
 *    every TANKING_REALERT_COOLDOWN_MS (15 min), plus an immediate ping
 *    whenever recovery stalls or reverses (itself floored by
 *    MIN_REALERT_GAP_MS so a wobbling rate can't retrigger every cycle - see
 *    that constant's doc). 2026-08-01: delivery downgraded from "identical to
 *    the emergency band" - direct owner feedback after a sticky, slowly-
 *    resolving low produced far more forced-alarm-volume sirens than the
 *    situation warranted ("more focused on shutting up my phone than trying
 *    to wait and see if my low goes up"). Tanking is now voice (ungated) +
 *    direct vibration + notification only - see forceAlarmVolumeAndPlaySound,
 *    which now skips entirely for this band. It does not loop continuously
 *    and does not force a screen takeover. No AlarmManager repeat chain - it
 *    rides the same natural check() cadence AlertCoordinator does.
 *  - BAND_EMERGENCY (value <= DEFAULT_FLOOR, 55): nonstop repeat until
 *    acknowledged or recovered, full-screen takeover, and its own tighter
 *    10-min emergency-contact timer.
 *
 * An active episode moves between bands mid-flight as the value crosses 55
 * (escalateToEmergency/deescalateToTanking). Both bands end only at
 * CriticalLowMath.RECOVERY_THRESHOLD (75) or an explicit acknowledgment -
 * climbing to 58 is not resolution, so neither the ladder nor the
 * emergency-contact timer resets on the way up.
 */
object CriticalLowSiren {
    private const val TAG = "CriticalLowSiren"
    private const val PREFS_NAME = "ahead_critical_low_siren"
    private const val KEY_ACTIVE = "active"
    private const val KEY_ACKNOWLEDGED = "acknowledged"
    private const val KEY_VALUE = "value"
    private const val KEY_TICK_COUNT = "tick_count"
    private const val KEY_LAST_TICK_AT = "last_tick_at_ms"
    private const val KEY_BAND = "band"
    private const val KEY_TANKING_LAST_FIRED_AT = "tanking_last_fired_at_ms"
    private const val KEY_TANKING_WAS_RECOVERING = "tanking_was_recovering"
    // Deepest ladder rung already announced this episode (see
    // CriticalLowMath.TANKING_RUNGS). Lower = worse. Sentinel
    // Int.MAX_VALUE means "nothing announced yet".
    private const val KEY_DEEPEST_RUNG_FIRED = "deepest_rung_fired"
    // Which band was active when the user acknowledged - see check()'s
    // acknowledgment handling for why "they dismissed it" is not a single
    // yes/no but depends on WHAT they dismissed.
    private const val KEY_ACKNOWLEDGED_BAND = "acknowledged_band"
    // Whether this episode has already armed its emergency-contact timer.
    // Survives band changes on purpose - see escalateToEmergency.
    private const val KEY_CONTACT_TIMER_ARMED = "contact_timer_armed"

    private const val BAND_EMERGENCY = "emergency"
    private const val BAND_TANKING = "tanking"

    const val CHANNEL_ID = "glucose_critical_low_emergency"
    private const val NOTIFICATION_ID = 2005 // distinct from AlertNotifier's 2001-2004
    private const val REQUEST_CODE = 4713 // distinct from AlarmScheduler(4711)/EmergencyAlertScheduler(4712)
    private const val REQUEST_CODE_DISMISS = 4715 // distinct from CriticalLowEmergencyScheduler(4714)

    private const val TICK_INTERVAL_MS = 25_000L

    // Roughly three CGM cycles (readings land about every 5 min). 2026-08-01:
    // widened from 10 to 15 min at the owner's explicit request, matching
    // AlertCoordinator's own RED_REALERT_COOLDOWN_MS now that tanking-band
    // delivery is voice+vibration rather than a forced siren - the ladder in
    // CriticalLowMath.TANKING_RUNGS still carries the "it got worse" pings
    // uncapped; this only carries the "it still hasn't got better" heartbeat.
    private const val TANKING_REALERT_COOLDOWN_MS = 15 * 60_000L

    // Floor under the "recovery just stalled/reversed" instant re-fire in
    // maybeReAlertTanking, mirroring AlertCoordinator.MIN_REALERT_GAP_MS for
    // the same reason: without it, a rate hovering right around zero could
    // flip the recovering flag every cycle and re-alert every cycle with it.
    // Ladder rung crossings are NOT gated by this - they're monotonic
    // low-water-mark events that can't spuriously repeat, so there's nothing
    // to fatigue-limit there.
    private const val MIN_REALERT_GAP_MS = 5 * 60_000L

    // Deliberately its own, tighter constant - NOT
    // EmergencyContactsPrefs.alertTimeoutMinutes() (the general red-alert
    // default, 15 min) - a value under the critical floor is more severe
    // than an ordinary red alert and earns a faster escalation to a human.
    // Emergency-band only - see the class doc on why tanking-band doesn't
    // arm its own contact timer.
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
     *  states in one call rather than only ever starting:
     *   - genuinely recovered (CriticalLowMath.hasRecovered, >= 70) -> the
     *     only condition that stops an active siren or clears an
     *     acknowledged episode, regardless of which band it was in.
     *     2026-08-01 fix: this used to stop as soon as the value merely
     *     climbed back above the 55 critical floor - e.g. 58, still a real
     *     low - because it checked isCriticalLow's negation instead of the
     *     actual recovery threshold. Since this runs far more often than
     *     tick()'s own (correct) recovery check, it almost always won that
     *     race, so the siren could go quiet well before a genuine recovery.
     *     Now both checks agree.
     *   - not recovered, already active -> reconcile band (escalate/
     *     de-escalate if the value crossed 55 since the last check) and run
     *     that band's own re-fire logic. See escalateToEmergency/
     *     deescalateToTanking/maybeReAlertTanking and the heartbeat-stall
     *     resurrection below.
     *   - not recovered, not active, value <= floor -> start a fresh
     *     BAND_EMERGENCY loop (unless already acknowledged - see below).
     *   - not recovered, not active, tanking (CriticalLowMath.isTanking) ->
     *     start a fresh BAND_TANKING episode: one full-strength alert, no
     *     repeat loop armed.
     *   - neither -> nothing to do.
     *   - acknowledged (already dismissed this episode) suppresses a fresh
     *     start in EITHER band until a genuine recovery clears it -
     *     dismissing a notification doesn't change actual blood sugar, so a
     *     still-low value right after dismissing must not read as new. */
    fun check(context: Context, value: Int, rate: Double? = null, floor: Int = CriticalLowMath.DEFAULT_FLOOR) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentlyActive = prefs.getBoolean(KEY_ACTIVE, false)

        if (CriticalLowMath.hasRecovered(value)) {
            if (currentlyActive) {
                Log.i(TAG, "value recovered ($value) - stopping siren")
                stop(appContext)
            } else if (prefs.getBoolean(KEY_ACKNOWLEDGED, false)) {
                Log.i(TAG, "value recovered ($value) - clearing acknowledged episode")
                prefs.edit { putBoolean(KEY_ACKNOWLEDGED, false) }
            }
            return
        }

        val isEmergency = CriticalLowMath.isCriticalLow(value, floor)

        if (currentlyActive) {
            val currentBand = prefs.getString(KEY_BAND, BAND_EMERGENCY)
            when {
                isEmergency && currentBand != BAND_EMERGENCY -> escalateToEmergency(appContext, prefs, value, rate)
                !isEmergency && currentBand == BAND_EMERGENCY -> deescalateToTanking(appContext, prefs, value)
                currentBand == BAND_EMERGENCY -> {
                    val lastTickAt = prefs.getLong(KEY_LAST_TICK_AT, 0L)
                    if (System.currentTimeMillis() - lastTickAt > HEARTBEAT_STALL_MS) {
                        Log.w(TAG, "siren marked active but no tick in over ${HEARTBEAT_STALL_MS / 1000}s - resurrecting")
                        stampTick(appContext, prefs)
                        fireTick(appContext, value, BAND_EMERGENCY)
                        scheduleNextTick(appContext)
                    }
                }
                else -> maybeReAlertTanking(appContext, prefs, value, rate)
            }
            return
        }

        if (prefs.getBoolean(KEY_ACKNOWLEDGED, false)) {
            // Same episode the user already dismissed - still not recovered,
            // but they've already seen it. Normally stay quiet until a real
            // recovery clears the flag above.
            //
            // EXCEPT when things have since got materially worse. Dismissing
            // a TANKING warning means "I know I'm dropping, I'm on it" - it
            // does NOT mean "and I accept silence if I end up at 45". The
            // 2026-08-01 audit found that it did exactly that: acknowledging
            // at 65 suppressed the emergency siren all the way down, because
            // this gate ran before the isEmergency check below and the flag
            // only ever cleared at 70. Deteriorating from an acknowledged
            // tanking episode into a true critical low is new information and
            // must override the acknowledgment. The reverse is deliberately
            // NOT true: acknowledging the emergency band suppresses
            // everything below it, since there is nothing worse to escalate
            // to and re-nagging someone actively treating a 45 is the alarm
            // fatigue this tier can least afford.
            val acknowledgedBand = prefs.getString(KEY_ACKNOWLEDGED_BAND, BAND_EMERGENCY)
            if (!(isEmergency && acknowledgedBand == BAND_TANKING)) return
            Log.w(TAG, "acknowledged tanking episode deteriorated to a critical low ($value) - overriding acknowledgment")
        }

        if (isEmergency) {
            Log.w(TAG, "critical low ($value <= $floor) - starting emergency siren")
            start(appContext, value, BAND_EMERGENCY, rate)
        } else if (CriticalLowMath.isTanking(value, rate, floor)) {
            Log.w(TAG, "tanking below ${CriticalLowMath.TANKING_ENTRY} ($value, rate=$rate) - opening the alert ladder")
            start(appContext, value, BAND_TANKING, rate)
        }
    }

    fun isActive(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ACTIVE, false)

    private fun start(context: Context, value: Int, band: String, rate: Double?) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            putBoolean(KEY_ACTIVE, true)
            putBoolean(KEY_ACKNOWLEDGED, false)
            putString(KEY_BAND, band)
            putInt(KEY_VALUE, value)
            putInt(KEY_TICK_COUNT, 0)
        }
        stampTick(appContext, prefs)
        fireTick(appContext, value, band) // always one full-strength alert, either band
        if (band == BAND_EMERGENCY) {
            scheduleNextTick(appContext)
            scheduleEmergencyContactAlert(appContext, value, rate)
        } else {
            prefs.edit {
                putLong(KEY_TANKING_LAST_FIRED_AT, System.currentTimeMillis())
                // Seed the ladder's low-water mark at whatever rung this
                // opening value already sits below, so entering partway down
                // (e.g. straight in at 64) doesn't then replay the rungs
                // above it that were never actually announced.
                CriticalLowMath.deepestRungCrossed(value)?.let { putInt(KEY_DEEPEST_RUNG_FIRED, it) }
            }
        }
    }

    /** Mid-episode crossing from tanking into a true critical low - arms
     *  the aggressive repeat chain and the tighter contact timer that a
     *  fresh emergency-band start would have gotten. */
    private fun escalateToEmergency(context: Context, prefs: android.content.SharedPreferences, value: Int, rate: Double?) {
        Log.w(TAG, "tanking episode crossed into true emergency ($value) - escalating")
        prefs.edit {
            putString(KEY_BAND, BAND_EMERGENCY)
            putInt(KEY_VALUE, value)
            putInt(KEY_TICK_COUNT, 0)
        }
        stampTick(context, prefs)
        fireTick(context, value, BAND_EMERGENCY)
        scheduleNextTick(context)
        // Deliberately NOT unconditional. A low fighting its way around the
        // floor (52 -> 57 -> 53 -> 58) crosses this boundary repeatedly, and
        // re-arming a fresh 10 minutes on every crossing would push the
        // emergency-contact text out indefinitely - nobody would ever be
        // called, precisely during the longest episodes. AlertCoordinator
        // documents this same hazard for its own timer; the 2026-08-01 audit
        // found the band logic had reintroduced it here.
        scheduleEmergencyContactAlert(context, value, rate)
    }

    /** Mid-episode climb back above the hard floor while still under the
     *  recovery threshold - drops the nonstop repeat chain and the tighter
     *  contact timer back down to tanking's calmer heartbeat. Explicitly
     *  stops the currently-looping ringtone/vibration rather than letting
     *  them run until their next natural re-assert, since "de-escalate"
     *  should be heard/felt immediately, not just decided internally. */
    private fun deescalateToTanking(context: Context, prefs: android.content.SharedPreferences, value: Int) {
        Log.i(TAG, "climbed above the emergency floor ($value) but not recovered - de-escalating to the ladder")
        prefs.edit {
            putString(KEY_BAND, BAND_TANKING)
            putInt(KEY_VALUE, value)
            putLong(KEY_TANKING_LAST_FIRED_AT, System.currentTimeMillis())
            // Seed the ladder at where the value actually IS now, so climbing
            // back up through rungs stays quiet (only downward moves ping).
            CriticalLowMath.deepestRungCrossed(value)?.let { rung ->
                putInt(KEY_DEEPEST_RUNG_FIRED, minOf(rung, prefs.getInt(KEY_DEEPEST_RUNG_FIRED, Int.MAX_VALUE)))
            }
        }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        alarmManager?.cancel(operationPendingIntent(context))
        // NOTE: deliberately does NOT cancel CriticalLowEmergencyScheduler.
        // Climbing from 52 to 58 is not resolution - the person is still
        // under the recovery threshold and still hasn't acknowledged
        // anything. Cancelling here (as this did before the 2026-08-01 audit)
        // meant an episode that oscillated across the floor could tear down
        // and re-arm the contact timer forever without it ever firing. Only
        // stop() - an explicit acknowledgment or a real recovery - ends it.
        ringtone?.let { runCatching { if (it.isPlaying) it.stop() } }
        ringtone = null
        runCatching { vibrator(context)?.cancel() }
    }

    /**
     * Tanking-band re-fire while still active. Three independent reasons to
     * ping, in priority order:
     *
     *  1. A NEW LADDER RUNG was crossed downward (73 -> 70 -> 67 -> 63).
     *     Fires regardless of this reading's rate: once an episode is open,
     *     reaching a worse rung is itself the news. Rate only ever gated
     *     OPENING the episode. Climbing back up through rungs never fires -
     *     that's recovery, and [CriticalLowMath.deepestRungCrossed] only
     *     moves the recorded low-water mark downward.
     *  2. RECOVERY STALLED OR REVERSED since the last check - new
     *     information, same urgency as a fresh drop (mirrors
     *     AlertCoordinator.fireRedIfWarranted's low-side logic).
     *  3. STILL NOT RESOLVED after [TANKING_REALERT_COOLDOWN_MS] - the
     *     "you're still down here" heartbeat.
     *
     * Suppressed entirely while genuinely recovering (rate > 0), so climbing
     * out stays quiet right up until [CriticalLowMath.hasRecovered] stops the
     * episode outright.
     */
    private fun maybeReAlertTanking(context: Context, prefs: android.content.SharedPreferences, value: Int, rate: Double?) {
        val previousDeepest = prefs.getInt(KEY_DEEPEST_RUNG_FIRED, Int.MAX_VALUE)
        val currentDeepest = CriticalLowMath.deepestRungCrossed(value)
        val crossedNewRung = currentDeepest != null && currentDeepest < previousDeepest

        val wasRecovering = prefs.getBoolean(KEY_TANKING_WAS_RECOVERING, false)
        val recovering = rate != null && rate > 0
        prefs.edit { putBoolean(KEY_TANKING_WAS_RECOVERING, recovering) }

        // A new rung outranks the recovery hush: a reading can show a
        // positive rate (CGM noise, or a genuine bounce that didn't hold)
        // while still having reached a materially worse number than anything
        // announced so far. That number is worth saying out loud.
        if (recovering && !crossedNewRung) return

        val lastFiredAt = prefs.getLong(KEY_TANKING_LAST_FIRED_AT, 0L)
        val now = System.currentTimeMillis()
        // Floored by MIN_REALERT_GAP_MS - see its doc.
        val recoveryJustStopped = wasRecovering && !recovering && now - lastFiredAt >= MIN_REALERT_GAP_MS
        if (!crossedNewRung && !recoveryJustStopped && now - lastFiredAt < TANKING_REALERT_COOLDOWN_MS) return

        if (crossedNewRung) Log.w(TAG, "crossed ladder rung $currentDeepest ($value) - re-alerting")
        stampTick(context, prefs)
        fireTick(context, value, BAND_TANKING)
        prefs.edit {
            putInt(KEY_VALUE, value)
            putLong(KEY_TANKING_LAST_FIRED_AT, now)
            if (currentDeepest != null) putInt(KEY_DEEPEST_RUNG_FIRED, minOf(currentDeepest, previousDeepest))
        }
    }

    private fun stampTick(context: Context, prefs: android.content.SharedPreferences) {
        prefs.edit { putLong(KEY_LAST_TICK_AT, System.currentTimeMillis()) }
    }

    /** Armed once per genuinely new episode (start() only - never on a
     *  resurrect/heartbeat re-fire, which would keep pushing the deadline
     *  out forever and defeat the point of a fixed unacknowledged-duration
     *  timeout). No-ops if the feature is off, same gate AlertCoordinator's
     *  own scheduleEmergencyAlert uses. */
    private fun scheduleEmergencyContactAlert(context: Context, value: Int, rate: Double?) {
        if (!EmergencyContactsPrefs.isEnabled(context)) return
        // One arm per episode, not per band crossing - see escalateToEmergency.
        // Cleared only by stop(), i.e. an explicit acknowledgment or a genuine
        // recovery, both of which mean the episode is actually over.
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_CONTACT_TIMER_ARMED, false)) {
            Log.d(TAG, "emergency-contact timer already armed for this episode - not restarting the clock")
            return
        }
        prefs.edit { putBoolean(KEY_CONTACT_TIMER_ARMED, true) }
        val message = EmergencyAlertRepository.messageFor(
            context,
            EmergencyAlertType.LOW,
            value,
            rate = rate,
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
        // De-escalation gate: don't wait on the next check() cycle to notice
        // a climb back above the hard floor - this loop only exists while
        // in the emergency band, so catch the transition as early as the
        // next tick, not whenever GlucoseStatusService next renders.
        if (raw != null && !CriticalLowMath.isCriticalLow(raw.value)) {
            deescalateToTanking(appContext, prefs, raw.value)
            return
        }
        val currentValue = raw?.value ?: prefs.getInt(KEY_VALUE, 0)

        prefs.edit { putInt(KEY_TICK_COUNT, tickCount) }
        stampTick(appContext, prefs)
        fireTick(appContext, currentValue, BAND_EMERGENCY) // only ever scheduled while in the emergency band
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
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Captured BEFORE the band is cleared - check() needs to know which
        // tier was acknowledged, because dismissing a tanking warning must
        // not suppress a later true emergency (see check()).
        val bandAtStop = prefs.getString(KEY_BAND, BAND_EMERGENCY) ?: BAND_EMERGENCY
        prefs.edit {
            putBoolean(KEY_ACTIVE, false)
            putBoolean(KEY_ACKNOWLEDGED, true)
            putString(KEY_ACKNOWLEDGED_BAND, bandAtStop)
            remove(KEY_VALUE)
            remove(KEY_TICK_COUNT)
            remove(KEY_LAST_TICK_AT)
            remove(KEY_BAND)
            remove(KEY_TANKING_LAST_FIRED_AT)
            remove(KEY_TANKING_WAS_RECOVERING)
            remove(KEY_CONTACT_TIMER_ARMED)
        }
        ringtone?.let { runCatching { if (it.isPlaying) it.stop() } }
        ringtone = null
        runCatching { vibrator(appContext)?.cancel() }
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        alarmManager?.cancel(operationPendingIntent(appContext))
        NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID)
        CriticalLowEmergencyScheduler.cancel(appContext)
    }

    /** [band] drives wording only - every delivery mechanism (forced volume,
     *  direct vibration, voice bypassing the master toggle) is identical
     *  either way. That's the whole point of BAND_TANKING: the same
     *  can't-miss delivery, earlier, before things get worse. */
    private fun fireTick(context: Context, value: Int, band: String) {
        ensureChannel(context)
        postNotification(context, value, band)
        forceAlarmVolumeAndPlaySound(context, band)
        vibrate(context, band)
        val spokenText = if (band == BAND_EMERGENCY) {
            "Emergency. Glucose is $value. This is a critical low. Treat now."
        } else {
            "Glucose is $value and dropping. Treat now, before this becomes a critical low."
        }
        VoiceAlertEngine.speak(context, VoiceAlertCategory.EMERGENCY, spokenText)
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Critical low emergency",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Fires at or below ${CriticalLowMath.DEFAULT_FLOOR} mg/dL, or earlier while falling fast " +
                "through ${CriticalLowMath.RECOVERY_THRESHOLD} - independent of the normal red alert"
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

    /** Emergency band gets the full-screen takeover, same as before. Tanking
     *  band deliberately does NOT force a screen takeover - "one strong
     *  alert" means unmistakable sound/vibration/voice/notification, not a
     *  forced lockout for something that isn't (yet) the true emergency; it
     *  opens the ordinary red-alert screen on tap instead, which is what a
     *  tanking episode actually is - a low-side red, delivered reliably. */
    private fun postNotification(context: Context, value: Int, band: String) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val tapIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            if (band == BAND_EMERGENCY) {
                RedAlertActivity.createCriticalEmergencyIntent(context, value)
            } else {
                RedAlertActivity.createIntent(context, value, projected = null, rate = null)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Swiping this away means the same thing as tapping "I'm treating
        // this" on the takeover screen - stops the whole siren loop, not
        // just the notification. 2026-08-01: previously setOngoing(true)
        // (unswipeable, in-app dismiss only) - reported as a real usability
        // downgrade in the exact moment it matters least (low and shaky is
        // not when you want extra steps). A plain swipe now works and
        // genuinely acknowledges the episode instead of leaving the loop
        // silently still armed with the notification just gone from view.
        val deleteIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_DISMISS,
            Intent(context, CriticalLowDismissReceiver::class.java).setAction(CriticalLowDismissReceiver.ACTION_DISMISSED),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val (title, text) = if (band == BAND_EMERGENCY) {
            "🚨 CRITICAL LOW: $value mg/dL" to "Treat now — swipe away once you're on it"
        } else {
            "🔴 Tanking: $value mg/dL, falling" to "Treat now before this becomes critical — swipe away once you're on it"
        }
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(NotificationIconFactory.warningIcon(context))
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(tapIntent)
            .setDeleteIntent(deleteIntent)
        // Debug-only: lets testing skip the forced lock-screen takeover while
        // keeping sound/vibration/voice/notification exactly as they'd
        // normally fire - see DebugAlertPrefs. Emergency band only - tanking
        // never sets a full-screen intent in the first place.
        if (band == BAND_EMERGENCY && !(BuildConfig.DEBUG && DebugAlertPrefs.isFullScreenDisabled(context))) {
            builder.setFullScreenIntent(tapIntent, true)
        }
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
    }

    /** Emergency band only. 2026-08-01: tanking band no longer forces alarm
     *  volume or plays the siren sound at all - owner feedback after a
     *  sticky low produced repeated forced-volume alarms for a situation that
     *  was resolving, just slowly. Tanking still gets voice (ungated,
     *  VoiceAlertEngine.UNGATED_CATEGORIES) and direct vibration from
     *  fireTick/vibrate - this function is the ONLY thing that changed;
     *  nothing else about "can't-miss delivery" for tanking was removed. */
    private fun forceAlarmVolumeAndPlaySound(context: Context, band: String) {
        if (band != BAND_EMERGENCY) return
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
            newRingtone.isLooping = band == BAND_EMERGENCY
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

    private fun vibrate(context: Context, band: String) {
        val v = vibrator(context) ?: return
        runCatching {
            if (!v.hasVibrator()) return
            // Emergency: repeat index 0 loops the whole pattern indefinitely
            // until cancel(). Tanking: -1 plays the pattern once and stops -
            // matches the ringtone's isLooping choice above, same reasoning.
            val repeat = if (band == BAND_EMERGENCY) 0 else -1
            v.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, repeat))
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
