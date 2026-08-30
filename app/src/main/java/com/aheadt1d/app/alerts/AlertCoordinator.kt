package com.aheadt1d.app.alerts

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.aheadt1d.app.BuildConfig
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
 *  - HIGH side: no peak/re-arm tracking - a fast fall from a high can still
 *    crash into a low, so "it's been falling for a while" is not a reason to
 *    go fully quiet, but there's no bounce-detection layered on top either.
 *    Just the flat RED_HIGH_REALERT_COOLDOWN_MS heartbeat (45 min), plus the
 *    correction-aware grace below (2026-08-26 note: an earlier version of
 *    this file had a fully-built peak/re-arm tracker described in this
 *    comment - confirmed dead code, never called - removed rather than
 *    wired in, since the flat cooldown + correction grace already cover the
 *    real cases that came up in practice).
 *
 * All decision state is persisted so it survives process death - critical,
 * because LatestTrendRepository.init() replays the last trend from disk on
 * every process start and the service renders immediately. Without persisted
 * dedup, every app restart mid-episode would re-alarm.
 *
 * REMOVED 2026-08-20, at the owner's explicit request: the full-screen
 * lockout takeover (RedAlertActivity), the forced-volume critical-low siren
 * (CriticalLowSiren), and the emergency-contact auto-text escalation
 * (emergency/) are all gone - reported as more of a headache (an alarm that
 * couldn't be dismissed) than a help for what the owner actually needs right
 * now. Red/yellow severity still fires as an ordinary notification (see
 * AlertNotifier) with voice alerts (kept, separately toggleable) - just
 * nothing that locks the screen, forces alarm-stream volume, or pages a
 * third party. The dismiss-cooldown machinery that existed only to protect
 * the takeover screen from re-popping right after being dismissed went with
 * it - there's no takeover screen left to protect.
 *
 * 2026-08-26: two related fixes/additions, both reported live off a real
 * episode (354 mg/dL, re-alerted 14 min apart while never actually clearing
 * red-adjacent territory):
 *
 * 1. Yellow/red flapping no longer bypasses RED_HIGH_REALERT_COOLDOWN_MS.
 *    handleRedTransition previously treated ANY entry into red (including
 *    re-entering from a brief yellow dip - the value never actually got back
 *    in range) as a "brand-new episode" and force-fired immediately. Only a
 *    genuinely fresh episode (entering from "none") does that now; a
 *    yellow-then-red flap falls through to the ordinary cooldown/grace path
 *    like any other heartbeat. The LOW side is deliberately NOT changed here
 *    - a low re-entering red after a brief yellow dip still always fires
 *    immediately, since a fluctuating low is exactly the kind of new
 *    information worth interrupting for (see fireRedIfWarranted).
 *
 * 2. Correction-aware re-alert grace, via PlateauCoordinator's read-only
 *    accessors (see that object's doc for the boundary exception). Logging a
 *    correction never suppresses the FIRST alert of an episode, and never
 *    suppresses while the value is still moving the wrong way (still
 *    climbing on a high, still falling on a low) - only holds off a repeat
 *    alert while the direction looks like the correction is doing its job.
 *    Low and high are asymmetric on purpose, per the owner: a low needs to
 *    resolve fast and shouldn't stay silenced long even with repeat
 *    corrections (LOW_CORRECTION_GRACE_MS, fixed 30 min from the FIRST
 *    correction - matches PlateauCoordinator's own non-extending anchor for
 *    the same window). A high can be legitimately managed over hours with
 *    several doses (HIGH_CORRECTION_GRACE_MS, rolling 90 min from the MOST
 *    RECENT correction - extends with every additional one logged).
 */
object AlertCoordinator {
    private const val PREFS_NAME = "ahead_alert_state"
    private const val KEY_LAST_DATE = "last_handled_trend_date"
    private const val KEY_LAST_SEVERITY = "last_handled_severity"
    private const val KEY_LAST_RED_FIRED_AT = "last_red_fired_at_ms"
    private const val KEY_SIGNAL_LOST_FIRED = "signal_lost_fired"
    private const val KEY_SIGNAL_LOST_LAST_FIRED_AT = "signal_lost_last_fired_at_ms"
    private const val KEY_LOW_WAS_RECOVERING = "low_was_recovering"
    private const val KEY_LOW_WAS_HELD = "low_was_held"
    private const val KEY_RED_LOW_SIDE = "red_low_side"
    private const val KEY_YELLOW_LAST_ALERTED_PROJECTED = "yellow_last_alerted_projected"
    private const val KEY_LAST_LOW_EVENT_AT = "last_low_event_at_ms"

    private const val RED_LOW_REALERT_COOLDOWN_MS = 15 * 60_000L
    // High-side red re-alert cooldown: set to 45 minutes to give insulin time
    // to take effect and avoid severe alarm fatigue on fluctuating highs
    // (e.g. 330 -> 300 -> 340 -> 290) that have already been treated.
    private const val RED_HIGH_REALERT_COOLDOWN_MS = 45 * 60_000L

    // Post-hypo recovery grace period: for 40 minutes after a treated low
    // (<= 80 mg/dL), intentional fast rises (+2.5, +3.5 mg/dL/min) and expected
    // rebound spikes stay completely silent unless glucose breaches 240 mg/dL.
    private const val POST_HYPO_RECOVERY_GRACE_WINDOW_MS = 40 * 60_000L
    private const val RECOVERY_REBOUND_CEILING_MGDL = 240
    // Floor under the low-side "recovery just stopped" instant re-fire below.
    // 2026-08-01: that rule had no minimum gap at all, so a low wobbling
    // right around a flat rate (e.g. -0.1/+0.1 noise, or a real but shallow
    // bounce) could flip the recovering flag every single cycle and re-post
    // the full-screen red takeover every cycle with it - reported as "too
    // many alarms" for a low that was just sticky, not worsening. A brand-new
    // episode (forceFire) is NOT gated by this - only the instant re-fire on
    // a sign flip is. Below this floor, a sign flip still updates the
    // recovering/state tracking, it just doesn't independently interrupt
    // again - the plain RED_LOW_REALERT_COOLDOWN_MS heartbeat still applies.
    private const val MIN_REALERT_GAP_MS = 5 * 60_000L
    // Correction-aware re-alert grace (2026-08-26, at the owner's request) -
    // see the class doc's item 2 for the full reasoning on why these are
    // asymmetric. Fixed, non-extending window on the low side (a low needs to
    // resolve fast); rolling, extends-per-correction window on the high side
    // (a high can be legitimately managed over hours with several doses).
    private const val LOW_CORRECTION_GRACE_MS = 30 * 60_000L
    private const val HIGH_CORRECTION_GRACE_MS = 90 * 60_000L
    // Same cadence as the red re-alert heartbeat - an ongoing blackout is at
    // least as urgent as an ongoing red glucose reading, and there's no
    // reason for it to go quiet just because the first alert already fired.
    private const val SIGNAL_LOST_REALERT_COOLDOWN_MS = 15 * 60_000L
    // Low-side red clear hysteresis. Once a critical LOW has fired red, the alert
    // is held up until the value climbs solidly past the danger band - not the
    // instant it nudges back over the floor - so a BG hovering near the cutoff
    // can't flicker the red alert on and off. Set to 80 mg/dL (updated 2026-08-20
    // at owner request) so recovery is clear and unambiguous.
    // High-side reds are deliberately NOT held this way (a fast fall from a high
    // is its own hazard, not something to latch).
    private const val LOW_RED_CLEAR_HYSTERESIS = 80
    // Roughly the middle of the 70-180 healthy band. Only used to infer which
    // direction counts as "worse" for the current yellow episode (lower vs
    // higher) - never to decide severity itself. (2026-08-28: that severity
    // decision used to be "entirely the backend's call," as this comment
    // used to say - it's entirely on-device now, via SeverityEngine in
    // ahead-rate-math, called from GlucoseDisplayState.toDisplayState. This
    // class never computes severity itself either way, just reacts to it.)
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
        if (AlertSilenceManager.isSilenced(context)) {
            AlertNotifier.cancelAlerts(context)
            AlertNotifier.cancelPlateau(context)
            AlertNotifier.cancelCorrection(context)
            return
        }
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
     * signal was lost). 2026-07-27: previously gated on prevSeverity being
     * yellow/red and delivered as yellow-tier - see showSignalLostAlert's doc
     * for the full reasoning on both changes.
     *
     * 2026-07-30: previously fired exactly once per dark period, then went
     * silent for however long the blackout continued - a real overnight gap
     * (Bluetooth reconnect failure, reader app dying in the background) could
     * run for hours on a single alert the person may have slept through.
     * Mirrors fireRedIfWarranted's heartbeat: first fire is immediate and
     * unconditional, then it re-alerts every SIGNAL_LOST_REALERT_COOLDOWN_MS
     * for as long as the blackout persists. See the Reading branch of
     * evaluate() for how the latch clears and forces a fresh announcement
     * when data resumes.
     */
    private fun handleStale(context: Context, prefs: android.content.SharedPreferences, stale: GlucoseDisplayState.Stale) {
        val alreadyFired = prefs.getBoolean(KEY_SIGNAL_LOST_FIRED, false)
        val lastFiredAt = prefs.getLong(KEY_SIGNAL_LOST_LAST_FIRED_AT, 0L)
        val now = System.currentTimeMillis()

        if (alreadyFired && now - lastFiredAt < SIGNAL_LOST_REALERT_COOLDOWN_MS) return

        AlertNotifier.showSignalLostAlert(
            context, stale.lastValue, stale.lastArrow, stale.ageMinutes,
            blockedReason = stale.blockedReason
        )
        prefs.edit {
            putBoolean(KEY_SIGNAL_LOST_FIRED, true)
            putLong(KEY_SIGNAL_LOST_LAST_FIRED_AT, now)
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

        if (reading.value <= LOW_RED_CLEAR_HYSTERESIS || isLowSide(reading.value, reading.projected)) {
            prefs.edit { putLong(KEY_LAST_LOW_EVENT_AT, now) }
        }

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
                "yellow" -> fireYellowIfWarranted(context, prefs, reading, forceFire = false, downgradedFromRed = false, suppressAlert, now)
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
            if (BuildConfig.DEBUG) {
                Log.d("AlertCoordinator", "low red held: value ${reading.value} < $LOW_RED_CLEAR_HYSTERESIS clear buffer")
            }
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
                fireYellowIfWarranted(
                    context, prefs, reading,
                    forceFire = prevSeverity != "yellow",
                    downgradedFromRed = prevSeverity == "red",
                    suppressAlert, now,
                )
            }
            else -> {
                AlertNotifier.cancelAlerts(context)
                if (prevSeverity == "red") clearRedEpisodeState(prefs)
                prefs.edit { remove(KEY_YELLOW_LAST_ALERTED_PROJECTED) }
            }
        }

        prefs.edit {
            putString(KEY_LAST_SEVERITY, severity)
            putLong(KEY_LAST_DATE, date)
        }
    }

    /** Low vs high isn't just "which side of 70 is the CURRENT value on" -
     *  a reading can still read high (e.g. 79) while already scored "red"
     *  because it's projected to crash through the low band within 15 min
     *  (fast negative rate). Classifying that as high-side would route it
     *  through the never-suppressed high-side heartbeat instead of the
     *  low-side recovery logic, AND - more importantly - would schedule the
     *  wrong EmergencyAlertType if it goes unacknowledged, texting an
     *  emergency contact that the person is HIGH while they're actually
     *  crashing low. 2026-08-01: found via a real episode that scored red
     *  at value=79/projected=67 and was misclassified high-side. See
     *  AlertThresholds.kt - this now delegates to the one shared copy of the
     *  same formula that AlertNotifier also uses. */

    /** Wipes per-episode state when a red episode ends, so the next one
     *  (low or high) starts from a clean slate instead of inheriting stale
     *  state from an unrelated earlier episode. */
    private fun clearRedEpisodeState(prefs: SharedPreferences) {
        prefs.edit {
            remove(KEY_LOW_WAS_RECOVERING)
            remove(KEY_LOW_WAS_HELD)
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
        // "Entering red" (structural bookkeeping - reseed the side-tracking
        // state that clearRedEpisodeState wipes on any red->yellow downgrade)
        // is not the same question as "should this force-fire immediately"
        // (see the class doc's item 1) - a yellow->red flap re-enters red
        // structurally but is NOT a fresh episode on the high side.
        val enteringRed = prevSeverity != "red"
        if (enteringRed) {
            prefs.edit {
                // Remember which side this episode is, so the clear-hysteresis
                // below only ever holds a LOW red (never a high one).
                putBoolean(KEY_RED_LOW_SIDE, isLowSide(reading.value, reading.projected))
            }
        }
        val forceFire = if (isLowSide(reading.value, reading.projected)) {
            enteringRed
        } else {
            // High side only: a yellow->red flap is the person never actually
            // getting out of the high, not new information - fall through to
            // the ordinary cooldown/grace path in fireRedIfWarranted instead
            // of bypassing it. Only a genuinely fresh episode (was in-range)
            // force-fires.
            prevSeverity == "none"
        }
        fireRedIfWarranted(context, prefs, reading, forceFire, now, lastRedFiredAt, suppressAlert)
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
        fireRedIfWarranted(context, prefs, reading, forceFire = false, now, lastRedFiredAt, suppressAlert)
    }

    /** Single decision point for both the transition and heartbeat paths -
     *  they differ only in whether a brand-new episode forces an immediate
     *  fire. Everything else (low-recovery suppression, high-side peak
     *  tracking, the plain cooldown heartbeat) is identical either way. */
    private fun fireRedIfWarranted(
        context: Context,
        prefs: SharedPreferences,
        reading: GlucoseDisplayState.Reading,
        forceFire: Boolean,
        now: Long,
        lastRedFiredAt: Long,
        suppressAlert: Boolean,
    ) {
        val value = reading.value
        val rate = reading.ratePerMinute

        if (isLowSide(value, reading.projected)) {
            val recovering = rate != null && rate > 0

            // Correction-aware grace: a logged low correction holds off a
            // follow-up alert for up to LOW_CORRECTION_GRACE_MS from when it
            // was FIRST logged (fixed - see PlateauCoordinator's
            // activeLowCorrectionAnchorMs doc for why this doesn't extend on
            // repeat corrections, unlike the high side below), as long as the
            // value isn't actively getting worse. Falling further despite a
            // logged correction is new information worth an immediate alert,
            // same as a fresh low.
            val correctionAnchor = PlateauCoordinator.activeLowCorrectionAnchorMs(context)
            val inCorrectionGrace = correctionAnchor != null && now - correctionAnchor < LOW_CORRECTION_GRACE_MS
            val worsening = rate != null && rate < 0
            val correctionHolding = inCorrectionGrace && !worsening

            val wasHeld = prefs.getBoolean(KEY_LOW_WAS_HELD, false)
            val held = recovering || correctionHolding
            prefs.edit {
                putBoolean(KEY_LOW_WAS_RECOVERING, recovering)
                putBoolean(KEY_LOW_WAS_HELD, held)
            }

            if (held && !forceFire) {
                // Suppress follow-ups while genuinely recovering OR while a
                // logged correction's grace window says "give it a moment" -
                // the person is already being warned/treating, so a repeat
                // here wouldn't change what they do next. A brand-new episode
                // still always fires once even if already rising at first
                // detection.
                return
            }
            // Held-state just stalled/reversed is new information worth an
            // immediate alert, same urgency as a fresh low - otherwise fall
            // back to the plain re-alert cooldown. Gated by MIN_REALERT_GAP_MS
            // (see its doc) so a wobbling rate can't retrigger this every
            // cycle - it only counts as "new information" if it's been at
            // least that long since the last actual alert.
            val heldJustStopped = wasHeld && !held && now - lastRedFiredAt >= MIN_REALERT_GAP_MS
            if ((forceFire || heldJustStopped || now - lastRedFiredAt >= RED_LOW_REALERT_COOLDOWN_MS) && !suppressAlert) {
                AlertNotifier.showRedAlert(context, value, reading.projected, rate, recovering = recovering, projectedExtended = reading.projectedExtended)
                prefs.edit { putLong(KEY_LAST_RED_FIRED_AT, now) }
            }
            return
        }

        // High side: 45-minute management window for treated highs.
        // Fluctuating highs (e.g. 330 -> 300 -> 340 -> 290) are already being
        // managed, so repeat alarms during the 45-min insulin action window
        // cause alarm fatigue. It only re-alerts once RED_HIGH_REALERT_COOLDOWN_MS
        // has elapsed or if it's a brand-new episode (forceFire).
        //
        // Correction-aware grace on top: a logged high correction holds off a
        // follow-up alert for up to HIGH_CORRECTION_GRACE_MS from the MOST
        // RECENT correction logged (rolling - see PlateauCoordinator's
        // activeHighCorrectionAnchorMs doc for why repeat corrections extend
        // this, unlike the low side above), as long as the value isn't still
        // climbing. Still climbing despite a logged correction is new
        // information worth an immediate alert, same urgency as a fresh high.
        val correctionAnchor = PlateauCoordinator.activeHighCorrectionAnchorMs(context)
        val inCorrectionGrace = correctionAnchor != null && now - correctionAnchor < HIGH_CORRECTION_GRACE_MS
        val stillClimbing = rate != null && rate > 0
        val correctionHolding = inCorrectionGrace && !stillClimbing

        if (correctionHolding && !forceFire) {
            return
        }

        if ((forceFire || now - lastRedFiredAt >= RED_HIGH_REALERT_COOLDOWN_MS) && !suppressAlert) {
            AlertNotifier.showRedAlert(context, value, reading.projected, rate, recovering = false, projectedExtended = reading.projectedExtended)
            prefs.edit { putLong(KEY_LAST_RED_FIRED_AT, now) }
        }
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
        downgradedFromRed: Boolean,
        suppressAlert: Boolean,
        now: Long = System.currentTimeMillis(),
    ) {
        val projected = reading.projected

        // Post-hypo recovery grace period: 40 minutes after treating a low,
        // intentional rises out of the low (e.g. drinking juice) are healthy
        // and expected. Mute yellow alerts while climbing under 240 mg/dL.
        val lastLowAt = prefs.getLong(KEY_LAST_LOW_EVENT_AT, 0L)
        val inPostHypoGraceWindow = now - lastLowAt <= POST_HYPO_RECOVERY_GRACE_WINDOW_MS
        val isRecoveringRise = reading.ratePerMinute != null && reading.ratePerMinute > 0 && reading.value < RECOVERY_REBOUND_CEILING_MGDL

        if (inPostHypoGraceWindow && isRecoveringRise) {
            if (BuildConfig.DEBUG) {
                Log.d("AlertCoordinator", "Yellow alert suppressed: in 40m post-hypo recovery grace window (value=${reading.value}, rate=${reading.ratePerMinute})")
            }
            if (projected != null) prefs.edit { putInt(KEY_YELLOW_LAST_ALERTED_PROJECTED, projected) }
            return
        }

        if (forceFire) {
            val isHighSide = (reading.projected ?: reading.value) >= YELLOW_MID_POINT
            val isFastRise = reading.ratePerMinute != null && reading.ratePerMinute >= 1.5
            val isEscalatedHigh = (reading.projected ?: reading.value) >= 240 || reading.value >= 240
            // 2026-08-26, real reported case: 298 red -> 283 yellow, falling
            // -3.1 mg/dL/min, still forced an audible ping purely because 283
            // is numerically >=240 - with zero regard for the fact that this
            // was an already-tracked, already-alerted episode actively
            // improving on its own (IOB working), not a fresh escalation. A
            // red episode resolving down into yellow while still falling is
            // improvement in progress - don't let isEscalatedHigh override
            // that just because the number is still big. Only applies to a
            // genuine downgrade-from-red; a brand-new episode (prevSeverity
            // "none") that happens to already be falling still alerts
            // normally, since there's no prior red episode it could be
            // "improving" from.
            val improvingFromRed = downgradedFromRed && isHighSide &&
                reading.ratePerMinute != null && reading.ratePerMinute < 0
            val shouldAudiblyAlert = (!isHighSide || isFastRise || isEscalatedHigh) && !improvingFromRed

            if (!suppressAlert && shouldAudiblyAlert) {
                AlertNotifier.showYellowAlert(context, reading.value, reading.projected, reading.ratePerMinute, projectedExtended = reading.projectedExtended)
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

}
