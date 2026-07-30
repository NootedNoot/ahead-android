package com.aheadt1d.app.alerts

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.aheadt1d.app.emergency.EmergencyAlertRepository
import com.aheadt1d.app.emergency.EmergencyAlertScheduler
import com.aheadt1d.app.emergency.EmergencyAlertType
import com.aheadt1d.app.emergency.EmergencyContactsPrefs
import com.aheadt1d.app.notifications.GlucoseDisplayState
import com.aheadt1d.app.state.LatestTrend

/**
 * Decides *when* the yellow/red alert notifications fire, from the same
 * tolerance-gated display state the ongoing notification renders. Called from
 * GlucoseStatusService.render() on every combine-emission and every 60s tick.
 *
 * Alarm semantics (chosen): one-shot + re-alert. The alarm sound plays once
 * per fired alert (no FLAG_INSISTENT loop); while severity stays red it
 * re-fires every RED_REALERT_COOLDOWN_MS so an unresolved low keeps
 * demanding attention without spamming on every 5-min backend score.
 *
 * Direction-aware on top of that (2025-07-14 session): a red reading is only
 * as loud as it needs to be to change what the person does next.
 *  - LOW side (value <= LOW_HIGH_SPLIT): while rising (recovering), follow-up
 *    alerts are suppressed - the person is already being warned/treating.
 *    The moment recovery stalls or reverses, it alerts immediately again,
 *    same urgency as a brand-new low.
 *  - HIGH side: never suppressed - a fast fall from a high can still crash
 *    into a low, so "it's been falling for a while" is not a reason to go
 *    quiet. Instead tracks a local peak: retreating <20 pts then climbing
 *    back up re-arms (still bouncing near the danger zone); retreating >=20
 *    pts (a real move toward safety) means climbing back up alone doesn't
 *    re-fire UNLESS it exceeds the original peak, which always fires. Both
 *    of those re-arm triggers require at least RED_HIGH_REARM_THRESHOLD_MGDL
 *    of movement (2026-07-19: previously any single mg/dL uptick counted,
 *    which let ordinary CGM sensor noise on a flat-but-noisy high re-fire
 *    every few minutes, bypassing RED_REALERT_COOLDOWN_MS entirely - a
 *    genuinely flat high still gets its heartbeat via the plain cooldown).
 *
 * All decision state is persisted so it survives process death - critical,
 * because LatestTrendRepository.init() replays the last trend from disk on
 * every process start and the service renders immediately. Without persisted
 * dedup, every app restart mid-episode would re-alarm.
 */
object AlertCoordinator {
    private const val PREFS_NAME = "ahead_alert_state"
    private const val KEY_LAST_DATE = "last_handled_trend_date"
    private const val KEY_LAST_SEVERITY = "last_handled_severity"
    private const val KEY_LAST_RED_FIRED_AT = "last_red_fired_at_ms"
    private const val KEY_SIGNAL_LOST_FIRED = "signal_lost_fired"
    private const val KEY_RED_PEAK_VALUE = "red_peak_value"
    private const val KEY_RED_LAST_VALUE = "red_last_value"
    private const val KEY_LOW_WAS_RECOVERING = "low_was_recovering"
    private const val KEY_RED_LOW_SIDE = "red_low_side"
    private const val KEY_YELLOW_LAST_ALERTED_PROJECTED = "yellow_last_alerted_projected"

    private const val RED_REALERT_COOLDOWN_MS = 15 * 60_000L
    // Same 70 mg/dL split the chart's low/high threshold lines and
    // RedAlertActivity's emergency-contact classifier already use - red only
    // ever fires for a critical low or critical high, never the 70-180 band,
    // so this cleanly separates the two without needing a dedicated field.
    private const val LOW_HIGH_SPLIT = 70
    private const val PEAK_RETREAT_REARM_THRESHOLD = 20
    // Low-side red clear hysteresis. Once a critical LOW has fired red, the alert
    // is held up until the value climbs solidly past the danger band - not the
    // instant it nudges back over the floor - so a BG hovering near the cutoff
    // can't flicker the red alert on and off. Set a buffer above LOW_HIGH_SPLIT
    // (70): reaching 75 is an unambiguous recovery, not a one-reading wobble.
    // High-side reds are deliberately NOT held this way (a fast fall from a high
    // is its own hazard, not something to latch).
    private const val LOW_RED_CLEAR_HYSTERESIS = 75
    // Minimum mg/dL movement for a high-side peak/climb-back to count as a
    // re-arm trigger - below this it's ordinary CGM sensor noise, not a
    // meaningful change. See the class doc's HIGH side note.
    private const val RED_HIGH_REARM_THRESHOLD_MGDL = 15
    // Roughly the middle of the 70-180 healthy band. Only used to infer which
    // direction counts as "worse" for the current yellow episode (lower vs
    // higher) - never to decide severity itself, which is entirely the
    // backend's call.
    private const val YELLOW_MID_POINT = 125
    // How much further into danger the 15-min projection has to move, past
    // wherever it was when the last yellow alert fired, before a second one
    // is worth interrupting for again. 2025-07-14: previously "one alert per
    // episode" full stop, regardless of how much worse it got - this mirrors
    // fireRedIfWarranted's direction-awareness one tier down.
    private const val YELLOW_MATERIAL_WORSENING_MGDL = 20

    /** Synchronized: the read-decide-persist sequence below must be atomic.
     *  Two concurrent callers both seeing the pre-alert state would each fire
     *  the alarm for the same episode. */
    @Synchronized
    fun evaluate(context: Context, state: GlucoseDisplayState, trend: LatestTrend?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        when (state) {
            is GlucoseDisplayState.Reading -> {
                // Fresh data after a dark period: clear the signal-lost latch
                // (so a later blackout can announce again) and cancel whatever
                // is sitting in the shared red/signal-lost slot right now - the
                // severity-transition logic below only touches that slot on an
                // actual severity CHANGE, but fresh data resuming at the exact
                // same severity bucket it was in before going stale is common
                // (the first fresh reading often lands before the backend's
                // next classification cycle), and that transition logic would
                // otherwise never fire, leaving stale "no data" content sitting
                // there indefinitely next to an already-live ongoing notification.
                //
                // ALSO reset KEY_LAST_SEVERITY to "none": while signal was lost
                // we had no way to know what happened, so glucose reconnecting
                // must always be announced fresh, at whatever severity it's
                // actually AT right now - never left to the ordinary cooldown/
                // peak-tracking heartbeat below, which assumes continuous
                // confirmed monitoring and could otherwise stay silent for a
                // still-critical reading just because it "isn't new" by date.
                // Forcing prevSeverity to "none" makes the transition logic
                // below treat ANY resumed severity (including a repeat red) as
                // a brand-new episode - the same forceFire path a genuinely
                // new episode already takes - so reconnecting to a still-red
                // reading re-announces immediately instead of waiting out a
                // cooldown calibrated for uninterrupted signal.
                if (prefs.getBoolean(KEY_SIGNAL_LOST_FIRED, false)) {
                    prefs.edit {
                        putBoolean(KEY_SIGNAL_LOST_FIRED, false)
                        putString(KEY_LAST_SEVERITY, "none")
                    }
                    AlertNotifier.cancelRed(context)
                }
                handleReading(context, prefs, state, trend)
            }
            is GlucoseDisplayState.Stale -> handleStale(context, prefs, state)
            GlucoseDisplayState.NoData -> { /* never had data - nothing to alert on */ }
        }
    }

    /**
     * Data has gone stale. Never cancel an in-flight red (losing signal isn't
     * an all-clear) - but DOES escalate itself, unconditionally, to the same
     * red-tier delivery as a live glucose alert: a total data blackout is
     * dangerous on its own, regardless of what the last confirmed severity
     * was (the person could be dropping or climbing fast starting the moment
     * signal was lost). Fires once per dark period; see the Reading branch of
     * evaluate() for how the latch clears and forces a fresh announcement
     * when data resumes. 2026-07-27: previously gated on prevSeverity being
     * yellow/red and delivered as yellow-tier - see showSignalLostAlert's doc
     * for the full reasoning on both changes.
     */
    private fun handleStale(context: Context, prefs: android.content.SharedPreferences, stale: GlucoseDisplayState.Stale) {
        val alreadyFired = prefs.getBoolean(KEY_SIGNAL_LOST_FIRED, false)
        if (!alreadyFired) {
            AlertNotifier.showSignalLostAlert(
                context, stale.lastValue, stale.lastArrow, stale.ageMinutes,
                blockedReason = stale.blockedReason
            )
            prefs.edit { putBoolean(KEY_SIGNAL_LOST_FIRED, true) }
            // Guarded by !alreadyFired, same as the alert itself - so this
            // arms exactly once per dark period, not once per render tick.
            scheduleEmergencyAlert(context, EmergencyAlertType.NO_DATA, stale.lastValue, rate = null)
        }
    }

    private fun handleReading(
        context: Context,
        prefs: SharedPreferences,
        reading: GlucoseDisplayState.Reading,
        trend: LatestTrend?,
    ) {
        val severity = reading.severity ?: "none"
        val date = trend?.date ?: 0L

        val prevSeverity = prefs.getString(KEY_LAST_SEVERITY, "none") ?: "none"
        val prevDate = prefs.getLong(KEY_LAST_DATE, 0L)
        val lastRedFiredAt = prefs.getLong(KEY_LAST_RED_FIRED_AT, 0L)
        val now = System.currentTimeMillis()

        // Set only by GlucoseCheckWorker for a manual "Check now" tap while
        // the app is foregrounded (see CheckNowSuppression) - a genuine
        // periodic/background crossing never sets this, so it still alerts
        // normally regardless of foreground state. Only gates the
        // interruptive show*Alert calls below; last-severity/date bookkeeping
        // and cancel* calls still run unconditionally so a real later
        // crossing isn't miscounted and stale alerts still get cleared.
        val suppressAlert = CheckNowSuppression.isSuppressed()
        if (suppressAlert) Log.d("CheckNow", "Suppressed alert - app foregrounded")

        // Exact same scored reading already handled (same severity AND date):
        // process-restart replay, the 60s tick, or a re-emission. Nothing new
        // to do except the re-alert heartbeats (which may still re-fire via
        // direction/peak/worsening tracking, not just a plain cooldown/state
        // gate) - red has a timed cooldown heartbeat, yellow doesn't (no
        // timer), but both can still re-fire here if things got materially
        // worse since the last alert.
        if (severity == prevSeverity && date == prevDate) {
            when (severity) {
                "red" -> handleRedHeartbeat(context, prefs, reading, now, lastRedFiredAt, suppressAlert)
                "yellow" -> fireYellowIfWarranted(context, prefs, reading, forceFire = false, suppressAlert)
            }
            return
        }

        // Low-side red clear hysteresis. A critical low that has fired red must
        // not clear the instant severity drops below the floor - a BG hovering
        // around the cutoff (e.g. 58 -> 62 -> 57) would otherwise cancel and
        // re-fire the red alert on every wobble. Hold the alert (leave it posted,
        // don't advance last-severity/date) until the value has climbed solidly
        // past the danger band. Only applies to LOW reds; a high red is never
        // latched. Deliberately does NOT route through fireRedIfWarranted: a hold
        // value can sit in the 70-75 band where that function's low/high split
        // would misclassify it - here we simply keep the existing red up.
        if (prevSeverity == "red" && severity != "red" &&
            prefs.getBoolean(KEY_RED_LOW_SIDE, false) &&
            reading.value < LOW_RED_CLEAR_HYSTERESIS
        ) {
            Log.d("AlertCoordinator", "low red held: value ${reading.value} < $LOW_RED_CLEAR_HYSTERESIS clear buffer")
            return
        }

        // A new scored reading (new date). Note a persisting red (or yellow)
        // episode produces a fresh date every backend cycle, so "still red/
        // yellow" must be distinguished from "just became red/yellow" -
        // otherwise every cycle would re-alarm and the cooldown/threshold
        // would never apply.
        when (severity) {
            "red" -> handleRedTransition(context, prefs, reading, prevSeverity, now, lastRedFiredAt, suppressAlert)
            "yellow" -> {
                // Downgrade from red cancels the red first.
                if (prevSeverity == "red") {
                    AlertNotifier.cancelRed(context)
                    clearRedEpisodeState(prefs)
                }
                // forceFire on entry (prevSeverity != "yellow") always posts and
                // seeds the baseline; a continuing episode only re-posts if the
                // projection has moved materially further into danger since the
                // last one shown - see fireYellowIfWarranted.
                fireYellowIfWarranted(context, prefs, reading, forceFire = prevSeverity != "yellow", suppressAlert)
            }
            else -> {
                if (prevSeverity != "none") AlertNotifier.cancelAlerts(context)
                if (prevSeverity == "red") clearRedEpisodeState(prefs)
                prefs.edit { remove(KEY_YELLOW_LAST_ALERTED_PROJECTED) }
            }
        }

        prefs.edit {
            putString(KEY_LAST_SEVERITY, severity)
            putLong(KEY_LAST_DATE, date)
        }
    }

    private fun isLowSide(value: Int): Boolean = value <= LOW_HIGH_SPLIT

    /** Wipes peak-tracking state when a red episode ends, so the next one
     *  (low or high) starts from a clean slate instead of inheriting a stale
     *  peak/last-value from an unrelated earlier episode. */
    private fun clearRedEpisodeState(prefs: SharedPreferences) {
        prefs.edit {
            remove(KEY_RED_PEAK_VALUE)
            remove(KEY_RED_LAST_VALUE)
            remove(KEY_LOW_WAS_RECOVERING)
            remove(KEY_RED_LOW_SIDE)
        }
    }

    /** First red-scored reading under a brand new trend.date - either a
     *  fresh episode (newlyRed) or the next backend cycle's re-score of an
     *  ongoing one. */
    private fun handleRedTransition(
        context: Context,
        prefs: SharedPreferences,
        reading: GlucoseDisplayState.Reading,
        prevSeverity: String,
        now: Long,
        lastRedFiredAt: Long,
        suppressAlert: Boolean,
    ) {
        val newlyRed = prevSeverity != "red"
        if (newlyRed) {
            prefs.edit {
                putInt(KEY_RED_PEAK_VALUE, reading.value)
                putInt(KEY_RED_LAST_VALUE, reading.value)
                // Remember which side this episode is, so the clear-hysteresis
                // below only ever holds a LOW red (never a high one).
                putBoolean(KEY_RED_LOW_SIDE, isLowSide(reading.value))
            }
        }
        fireRedIfWarranted(context, prefs, reading, forceFire = newlyRed, newlyRed = newlyRed, now, lastRedFiredAt, suppressAlert)
    }

    /** Same (severity="red", date) as last handled - a 60s-tick re-emission
     *  between backend cycles, not a new score. Direction/peak state can
     *  still change here (the value itself may differ from what was last
     *  evaluated even though the backend's trend.date hasn't moved yet). */
    private fun handleRedHeartbeat(
        context: Context,
        prefs: SharedPreferences,
        reading: GlucoseDisplayState.Reading,
        now: Long,
        lastRedFiredAt: Long,
        suppressAlert: Boolean,
    ) {
        fireRedIfWarranted(context, prefs, reading, forceFire = false, newlyRed = false, now, lastRedFiredAt, suppressAlert)
    }

    /** Single decision point for both the transition and heartbeat paths -
     *  they differ only in whether a brand-new episode forces an immediate
     *  fire. Everything else (low-recovery suppression, high-side peak
     *  tracking, the plain cooldown heartbeat) is identical either way.
     *
     *  [newlyRed] is threaded through separately from [forceFire] (today
     *  they're only ever true together, since handleRedTransition is the
     *  only caller that ever passes forceFire=true) so the emergency-contact
     *  timer below is gated on an explicit, self-documented "is this a brand
     *  new episode" condition rather than an implicit coincidence with
     *  forceFire's other meaning (post immediately). It must NEVER arm on a
     *  cooldown/peak-tracking heartbeat repost of an already-ongoing episode -
     *  RED_REALERT_COOLDOWN_MS happens to also be 15 minutes, so if every
     *  repost rearmed the clock, an unresolved crisis would defer the auto-
     *  text forever instead of ever actually reaching the emergency contact. */
    private fun fireRedIfWarranted(
        context: Context,
        prefs: SharedPreferences,
        reading: GlucoseDisplayState.Reading,
        forceFire: Boolean,
        newlyRed: Boolean,
        now: Long,
        lastRedFiredAt: Long,
        suppressAlert: Boolean,
    ) {
        val value = reading.value
        val rate = reading.ratePerMinute

        if (isLowSide(value)) {
            val wasRecovering = prefs.getBoolean(KEY_LOW_WAS_RECOVERING, false)
            val recovering = rate != null && rate > 0
            prefs.edit { putBoolean(KEY_LOW_WAS_RECOVERING, recovering) }

            if (recovering && !forceFire) {
                // Suppress follow-ups while genuinely recovering - the person
                // is already being warned/treating, so a repeat here wouldn't
                // change what they do next. A brand-new episode still always
                // fires once even if already rising at first detection.
                return
            }
            // Recovery just stalled/reversed is new information worth an
            // immediate alert, same urgency as a fresh low - otherwise fall
            // back to the plain re-alert cooldown.
            val recoveryJustStopped = wasRecovering && !recovering
            if ((forceFire || recoveryJustStopped || now - lastRedFiredAt >= RED_REALERT_COOLDOWN_MS) && !suppressAlert) {
                AlertNotifier.showRedAlert(context, value, reading.projected, rate, recovering = recovering)
                prefs.edit { putLong(KEY_LAST_RED_FIRED_AT, now) }
                if (newlyRed) scheduleEmergencyAlert(context, EmergencyAlertType.LOW, value, rate)
            }
            return
        }

        // High side: never suppressed. Peak tracking only ever adds an
        // extra reason to fire sooner (exceeding the peak, or bouncing back
        // up within 20 pts of it) - it never blocks the plain cooldown
        // heartbeat, which is what keeps a long, steady fall from a high
        // loud the whole way down (it can still crash into a low).
        val rearm = updateHighSideTracking(prefs, value)
        if ((forceFire || rearm.exceededPeak || rearm.climbingBack || now - lastRedFiredAt >= RED_REALERT_COOLDOWN_MS) && !suppressAlert) {
            AlertNotifier.showRedAlert(context, value, reading.projected, rate, recovering = false)
            prefs.edit { putLong(KEY_LAST_RED_FIRED_AT, now) }
            if (newlyRed) scheduleEmergencyAlert(context, EmergencyAlertType.HIGH, value, rate)
        }
    }

    /** Arms the 15-minute emergency-contact timer for a genuinely new red
     *  episode (or, via the NO_DATA overload in handleStale, a new dark
     *  period). No-ops entirely when the feature is off, so a disabled
     *  feature never even schedules a wakeup for nothing. */
    private fun scheduleEmergencyAlert(context: Context, type: EmergencyAlertType, value: Int, rate: Double?) {
        if (!EmergencyContactsPrefs.isEnabled(context)) return
        // Read once, reused for both the message text and the actual alarm
        // delay below - see EmergencyAlertScheduler.schedule's doc for why
        // this can't be two independent reads.
        val timeoutMinutes = EmergencyContactsPrefs.alertTimeoutMinutes(context)
        val message = EmergencyAlertRepository.messageFor(context, type, value, rate, timeoutMinutes)
        EmergencyAlertScheduler.schedule(context, type, message, timeoutMinutes)
    }

    /**
     * Single decision point for both the yellow-entry and continuing-episode
     * paths, mirroring fireRedIfWarranted's shape one tier down: forceFire
     * always posts (a brand-new episode) and (re)seeds the comparison
     * baseline; otherwise a second alert only fires if the 15-min projection
     * has moved at least [YELLOW_MATERIAL_WORSENING_MGDL] further into danger
     * than wherever it was when the last yellow alert fired - direction
     * inferred from which side of [YELLOW_MID_POINT] the projection sits on.
     *
     * If projected is null (no current backend trend to read one from - see
     * GlucoseStatusService's tolerance gate), there's nothing to compare
     * against, so this stays quiet rather than guessing; the plain state-gate
     * behavior from before this change is the fallback in that case.
     */
    private fun fireYellowIfWarranted(
        context: Context,
        prefs: SharedPreferences,
        reading: GlucoseDisplayState.Reading,
        forceFire: Boolean,
        suppressAlert: Boolean,
    ) {
        val projected = reading.projected

        if (forceFire) {
            if (!suppressAlert) {
                AlertNotifier.showYellowAlert(context, reading.value, reading.projected, reading.ratePerMinute)
            }
            if (projected != null) prefs.edit { putInt(KEY_YELLOW_LAST_ALERTED_PROJECTED, projected) }
            return
        }

        if (projected == null || suppressAlert) return

        val lastAlertedProjected = prefs.getInt(KEY_YELLOW_LAST_ALERTED_PROJECTED, projected)
        val isLowSide = projected < YELLOW_MID_POINT
        val worsenedBy = if (isLowSide) lastAlertedProjected - projected else projected - lastAlertedProjected

        if (worsenedBy >= YELLOW_MATERIAL_WORSENING_MGDL) {
            AlertNotifier.showYellowAlert(context, reading.value, reading.projected, reading.ratePerMinute)
            prefs.edit { putInt(KEY_YELLOW_LAST_ALERTED_PROJECTED, projected) }
        }
    }

    private data class HighSideRearm(val exceededPeak: Boolean, val climbingBack: Boolean)

    /** Updates (and persists) the running peak + last-seen value for the
     *  active high-side episode, and reports whether this update alone is
     *  reason to re-fire: a new peak that clears the old one by at least
     *  [RED_HIGH_REARM_THRESHOLD_MGDL] always is; climbing back up by at
     *  least that same threshold while still within [PEAK_RETREAT_REARM_THRESHOLD]
     *  points of the peak is (still bouncing near the danger zone); climbing
     *  back up after a real >=20pt retreat is NOT, unless it's also a
     *  material new peak. The peak itself still ratchets to the true running
     *  max on every call regardless of threshold, so retreat/climb distances
     *  stay accurate even across a run of sub-threshold noise. */
    private fun updateHighSideTracking(prefs: SharedPreferences, value: Int): HighSideRearm {
        val peak = prefs.getInt(KEY_RED_PEAK_VALUE, value)
        val lastValue = prefs.getInt(KEY_RED_LAST_VALUE, value)

        val exceededPeak = value - peak >= RED_HIGH_REARM_THRESHOLD_MGDL
        val newPeak = maxOf(peak, value)
        val retreatFromPeak = newPeak - value
        val climbingBack = !exceededPeak &&
            value - lastValue >= RED_HIGH_REARM_THRESHOLD_MGDL &&
            retreatFromPeak < PEAK_RETREAT_REARM_THRESHOLD

        prefs.edit {
            putInt(KEY_RED_PEAK_VALUE, newPeak)
            putInt(KEY_RED_LAST_VALUE, value)
        }
        return HighSideRearm(exceededPeak, climbingBack)
    }
}
