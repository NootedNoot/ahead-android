package com.aheadt1d.app.alerts

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.aheadt1d.app.BuildConfig
import com.aheadt1d.app.notifications.GlucoseDisplayState
import com.aheadt1d.app.state.LatestTrend
import org.aheadt1d.ratemath.SeverityEngine

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
    private const val KEY_LAST_YELLOW_FIRED_AT = "last_yellow_fired_at_ms"
    // 2026-09-23 ticket ("value vs rate gating"): consecutive-reading streaks backing
    // LowAlertPhase - see updateLowPhase's own doc for exactly how each is scored.
    private const val KEY_LOW_WORSENING_STREAK = "low_worsening_streak"
    private const val KEY_LOW_STABILITY_STREAK = "low_stability_streak"
    // Set the first time a real reading is ever handled, so the NoData branch can tell a fresh
    // install (nothing to say) from an app that HAD a reading and has since lost it entirely.
    private const val KEY_HAS_EVER_HAD_READING = "has_ever_had_reading"
    private const val KEY_LAST_KNOWN_VALUE = "last_known_value"
    private const val KEY_LAST_KNOWN_TIME = "last_known_time_ms"
    private const val KEY_LAST_KNOWN_ARROW = "last_known_arrow"

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
    // 2026-09-23: 90 -> 70 min at the owner's request - mid-point of rapid-acting insulin's
    // typical 60-90 min peak. Sustained highs carry real cost for him (CKD), so the high side is
    // deliberately a bit less forgiving than the textbook window. Still rolling per correction.
    private const val HIGH_CORRECTION_GRACE_MS = 70 * 60_000L
    // Same cadence as the red re-alert heartbeat - an ongoing blackout is at
    // least as urgent as an ongoing red glucose reading, and there's no
    // reason for it to go quiet just because the first alert already fired.
    private const val SIGNAL_LOST_REALERT_COOLDOWN_MS = 15 * 60_000L
    private const val SIGNAL_LOST_DROPPING_REALERT_COOLDOWN_MS = 10 * 60_000L
    // Low-side red clear buffer - streak-based, not a fixed mg/dL band (2026-09-23,
    // replacing the old flat LOW_RED_CLEAR_HYSTERESIS = 80). That old band held the alert (and
    // its "Still low... rising" copy) up to 80 mg/dL regardless of the app's own 70 mg/dL
    // threshold - real bug, reported live: a 79 mg/dL reading, already above 70, still showed
    // "Still low: 79 mg/dL, rising." Per the owner's explicit ticket: the low/not-low LABEL now
    // gates strictly on value (and projection) vs LOW_HIGH_SPLIT (70) with no rate dependency -
    // see LowAlertPhase's doc - while a SEPARATE stability buffer (this constant) still holds
    // off fully clearing the episode (cancelling the notification, letting severity flow
    // through as normal) until LOW_STABILITY_READINGS_REQUIRED consecutive readings hold or
    // keep climbing at/above that threshold. This is what catches a bounce that hasn't finished
    // (real case: 79 -> 87 -> 89 -> 83 @ -1.2 mg/dL/min) instead of prematurely signaling
    // recovery on the first good tick - though, being reactive rather than predictive, it's a
    // meaningful reduction in false-clears, not a guarantee against every possible bounce shape.
    private const val LOW_STABILITY_READINGS_REQUIRED = 2
    // Urgency escalation (URGENT vs STANDARD copy/tone) requires a SUSTAINED negative rate too -
    // two or more consecutive readings at/below SeverityEngine.RATE_FALLING_TRIGGER, not one
    // noisy blip - see LowAlertPhase's doc for the full reasoning.
    private const val LOW_WORSENING_READINGS_REQUIRED = 2

    // High-side red clear hysteresis. Once a critical HIGH has fired red (projected >= 250),
    // hold the red state while projection remains >= 235 (or value >= 200) unless the rate
    // is actively falling (<= -0.5 mg/dL/min). Hovering near 235-245 with rate wobbling
    // (+0.3 to +1.0) would otherwise flap red->yellow->red every 5 minutes, causing severe
    // alert fatigue while insulin is already working. Holding red preserves the 45-min high
    // cooldown so the user is not pestered while already treating.
    private const val HIGH_RED_CLEAR_HYSTERESIS_PROJECTED = 235
    private const val HIGH_RED_CLEAR_HYSTERESIS_VALUE = 200
    internal const val HIGH_RED_HOLD_MAX_DURATION_MS = 60 * 60_000L
    internal const val KEY_HIGH_RED_HELD_SINCE = "high_red_held_since_ms"

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
    // Minimum gap between two AUDIBLE yellow alerts, mirroring MIN_REALERT_GAP_MS one tier down.
    // 2026-09-20: yellow had no time-based cooldown of any kind. Entry into yellow
    // (forceFire = prevSeverity != "yellow") always posted, and the "none" branch wipes
    // KEY_YELLOW_LAST_ALERTED_PROJECTED on the way past, so the material-worsening gate never
    // applied to a flap. A value parked on the boundary - the 15-min projection crossing 80 back
    // and forth, which is ordinary CGM noise - therefore alarmed on EVERY re-entry: measured 6
    // alerts in one simulated hour. Same alarm-fatigue shape as the 2026-08-01 red
    // "recovery just stopped" bug that MIN_REALERT_GAP_MS was added to fix.
    // Deliberately does NOT gate the material-worsening path below - that one is real new
    // information and still fires regardless of this floor.
    private const val MIN_YELLOW_REALERT_GAP_MS = 15 * 60_000L

    /** Synchronized: the read-decide-persist sequence below must be atomic.
     *  Two concurrent callers both seeing the pre-alert state would each fire
     *  the alarm for the same episode. */
    @Synchronized
    fun evaluate(context: Context, state: GlucoseDisplayState, trend: LatestTrend?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (AlertSilenceManager.isSilenced(context)) {
            AlertNotifier.cancelAlerts(context)
            AlertNotifier.cancelPlateau(context)
            AlertNotifier.cancelCorrection(context)
            // 2026-09-20: a total data blackout is the ONE thing silence must not hide. This
            // gate used to sit above the `when (state)` below, so silencing glucose alerts also
            // disabled "I cannot see your glucose" - and silenceIndefinitely() never expires and
            // is reachable from the main screen, while the ongoing notification carried no
            // indication that anything was muted. The phone could sit there showing a last-known
            // number, looking like it was monitoring, for hours. handleStale's own doc already
            // argues a blackout "is dangerous on its own, regardless of what the last confirmed
            // severity was"; silence was quietly overriding that.
            // The alert is posted MUTED (see showSignalLostAlert's allowWhileSilenced) - visible
            // and honest, but it never overrides the quiet the person actually asked for.
            if (state is GlucoseDisplayState.Stale) handleStale(context, prefs, state, silenced = true)
            if (state is GlucoseDisplayState.NoData && prefs.getBoolean(KEY_HAS_EVER_HAD_READING, false)) {
                val lastTime = prefs.getLong(KEY_LAST_KNOWN_TIME, 0L)
                val arrowName = prefs.getString(KEY_LAST_KNOWN_ARROW, null)
                val arrow = arrowName?.let { runCatching { com.aheadt1d.app.notifications.GlucoseTrendArrow.valueOf(it) }.getOrNull() }
                    ?: com.aheadt1d.app.notifications.GlucoseTrendArrow.FLAT
                handleStale(
                    context,
                    prefs,
                    GlucoseDisplayState.Stale(
                        lastValue = prefs.getInt(KEY_LAST_KNOWN_VALUE, 0),
                        lastReadingTime = lastTime,
                        ageMinutes = if (lastTime > 0L) (System.currentTimeMillis() - lastTime) / 60_000 else 0L,
                        lastArrow = arrow,
                    ),
                    silenced = true,
                )
            }
            return
        }
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
            GlucoseDisplayState.NoData -> {
                // A fresh install genuinely has nothing to say. But "no reading at all" is only
                // harmless when we never had one: if a reading was handled at some point and the
                // stored reading has since vanished, that is a blackout wearing a different
                // costume, and returning silently here would be the app's only completely quiet
                // failure mode. Reuses the same stale path (and the same 15-min re-alert
                // heartbeat) off the last value we did see.
                if (prefs.getBoolean(KEY_HAS_EVER_HAD_READING, false)) {
                    val lastTime = prefs.getLong(KEY_LAST_KNOWN_TIME, 0L)
                    val arrowName = prefs.getString(KEY_LAST_KNOWN_ARROW, null)
                    val arrow = arrowName?.let { runCatching { com.aheadt1d.app.notifications.GlucoseTrendArrow.valueOf(it) }.getOrNull() }
                        ?: com.aheadt1d.app.notifications.GlucoseTrendArrow.FLAT
                    handleStale(
                        context, prefs,
                        GlucoseDisplayState.Stale(
                            lastValue = prefs.getInt(KEY_LAST_KNOWN_VALUE, 0),
                            lastReadingTime = lastTime,
                            ageMinutes = if (lastTime > 0L) (System.currentTimeMillis() - lastTime) / 60_000 else 0L,
                            lastArrow = arrow,
                        ),
                    )
                }
            }
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
    private fun handleStale(
        context: Context,
        prefs: android.content.SharedPreferences,
        stale: GlucoseDisplayState.Stale,
        silenced: Boolean = false,
    ) {
        val alreadyFired = prefs.getBoolean(KEY_SIGNAL_LOST_FIRED, false)
        val lastFiredAt = prefs.getLong(KEY_SIGNAL_LOST_LAST_FIRED_AT, 0L)
        val now = System.currentTimeMillis()

        val isDropping = stale.lastArrow == com.aheadt1d.app.notifications.GlucoseTrendArrow.SLOWLY_FALLING ||
                         stale.lastArrow == com.aheadt1d.app.notifications.GlucoseTrendArrow.DOWN ||
                         stale.lastArrow == com.aheadt1d.app.notifications.GlucoseTrendArrow.DOUBLE_DOWN
        val cooldown = if (isDropping) SIGNAL_LOST_DROPPING_REALERT_COOLDOWN_MS else SIGNAL_LOST_REALERT_COOLDOWN_MS

        if (alreadyFired && now - lastFiredAt < cooldown) return

        AlertNotifier.showSignalLostAlert(
            context, stale.lastValue, stale.lastArrow, stale.ageMinutes,
            blockedReason = stale.blockedReason,
            allowWhileSilenced = silenced
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
        val date = if (reading.readingTime > 0L) reading.readingTime else (trend?.date ?: 0L)

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

        // SeverityEngine.DEFAULT_YELLOW_LOW (80), not LOW_HIGH_SPLIT (70): this marker feeds the
        // post-hypo recovery grace window below, which is deliberately about "a treated low just
        // happened" more broadly than the strict 70 mg/dL low/not-low label - unrelated to the
        // low-side red stability buffer (see LOW_STABILITY_READINGS_REQUIRED's doc).
        if (reading.value <= SeverityEngine.DEFAULT_YELLOW_LOW || isLowSide(reading.value, reading.projected)) {
            prefs.edit { putLong(KEY_LAST_LOW_EVENT_AT, now) }
        }

        // Lets the NoData branch of evaluate() distinguish a fresh install (silent, correctly)
        // from an app that had a reading and has since lost it entirely (a blackout).
        prefs.edit {
            putBoolean(KEY_HAS_EVER_HAD_READING, true)
            putInt(KEY_LAST_KNOWN_VALUE, reading.value)
            putLong(KEY_LAST_KNOWN_TIME, if (reading.readingTime > 0L) reading.readingTime else now)
            putString(KEY_LAST_KNOWN_ARROW, reading.arrow.name)
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

        // Red clear hysteresis (Low and High).
        // A critical episode that has fired red must not clear the instant severity drops below
        // the boundary - a BG hovering around the cutoff would otherwise cancel and re-fire on
        // every wobble (severe alert fatigue). Hold the alert (leave it posted, don't advance
        // last-severity/date) until the reading has solidly exited the danger band.
        val isLowRed = prefs.getBoolean(KEY_RED_LOW_SIDE, false)
        if (prevSeverity == "red" && severity != "red") {
            if (isLowRed) {
                val previousStabilityStreak = prefs.getInt(KEY_LOW_STABILITY_STREAK, 0)
                val phase = updateLowPhase(prefs, reading.value, reading.projected, reading.ratePerMinute)
                val stabilityStreak = prefs.getInt(KEY_LOW_STABILITY_STREAK, 0)

                if (stabilityStreak < LOW_STABILITY_READINGS_REQUIRED) {
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            "AlertCoordinator",
                            "low red held: value ${reading.value}, phase $phase, " +
                                "stability streak $stabilityStreak/$LOW_STABILITY_READINGS_REQUIRED",
                        )
                    }
                    // A reading that just broke an already-building stability streak (value
                    // dropped back under the threshold, or rate went negative again after
                    // holding/climbing) is new information worth an immediate re-alert - same
                    // urgency as a fresh low, and specifically what catches a bounce still in
                    // progress (see LOW_STABILITY_READINGS_REQUIRED's doc) instead of silently
                    // continuing to hold as if nothing changed.
                    val justReversed = previousStabilityStreak > 0 && stabilityStreak == 0
                    // Unconditional (no isNotificationPosted gate) - unlike the high-side hold
                    // below, which only reposts when dismissed. nm.notify() with the same id
                    // updates a still-posted notification in place rather than duplicating it, so
                    // calling this every cycle both restores a dismissed notification (the
                    // original Gap 2 fix this replaces) AND keeps a still-posted one's visible
                    // text current - real bug this closes: a live "Still low: 79 mg/dL, rising"
                    // reading a value the app's own 70 mg/dL threshold says is fine sitting
                    // unchanged in the tray because nothing had dismissed it yet. silent=true
                    // (unless justReversed) means this never re-buzzes/re-speaks - see
                    // showRedAlert's own silent-return doc.
                    if (!suppressAlert) {
                        AlertNotifier.showRedAlert(
                            context, reading.value, reading.projected, reading.ratePerMinute,
                            lowPhase = phase,
                            projectedExtended = reading.projectedExtended,
                            silent = !justReversed,
                        )
                        if (justReversed) prefs.edit { putLong(KEY_LAST_RED_FIRED_AT, now) }
                    }
                    recordAlertAction(context, date, if (justReversed) "audible_red" else "held_low_red")
                    return
                }
                // Stability requirement met - fall through to the ordinary severity handling
                // below, which actually clears the episode (cancels the red alert, resets
                // episode state) for whatever this reading's real severity is.
            } else if (!isLowRed) {
                val proj = reading.projected ?: reading.value
                val isActivelyFalling = reading.ratePerMinute != null && reading.ratePerMinute <= -0.5
                val heldSince = prefs.getLong(KEY_HIGH_RED_HELD_SINCE, 0L)
                val holdDurationExceeded = heldSince > 0L && (now - heldSince >= HIGH_RED_HOLD_MAX_DURATION_MS)

                val shouldHoldHighRed = !holdDurationExceeded &&
                    reading.value >= HIGH_RED_CLEAR_HYSTERESIS_VALUE &&
                    proj >= HIGH_RED_CLEAR_HYSTERESIS_PROJECTED &&
                    !isActivelyFalling

                if (shouldHoldHighRed) {
                    if (heldSince == 0L) {
                        prefs.edit { putLong(KEY_HIGH_RED_HELD_SINCE, now) }
                    }
                    if (BuildConfig.DEBUG) {
                        Log.d("AlertCoordinator", "high red held: value ${reading.value}, proj $proj >= $HIGH_RED_CLEAR_HYSTERESIS_PROJECTED")
                    }
                    if (!isNotificationPosted(context, AlertNotifier.RED_ALERT_NOTIFICATION_ID) && !suppressAlert) {
                        AlertNotifier.showRedAlert(
                            context, reading.value, reading.projected, reading.ratePerMinute,
                            // No lowPhase passed - high side, defaults to the URGENT copy/tone
                            // (unaffected by the low-side-only LowAlertPhase system).
                            projectedExtended = reading.projectedExtended,
                            silent = true,
                        )
                    }
                    recordAlertAction(context, date, "held_high_red")
                    return
                } else if (holdDurationExceeded) {
                    prefs.edit { remove(KEY_HIGH_RED_HELD_SINCE) }
                    if (BuildConfig.DEBUG) {
                        Log.d("AlertCoordinator", "high red hold duration expired (60m cap reached); releasing hold")
                    }
                }
            }
        }

        // A new scored reading (new date). Note a persisting red (or yellow)
        // episode produces a fresh date every backend cycle, so "still red/
        // yellow" must be distinguished from "just became red/yellow" -
        // otherwise every cycle would re-alarm and the cooldown/threshold
        // would never apply.
        when (severity) {
            "red" -> handleRedTransition(context, prefs, reading, prevSeverity, now, lastRedFiredAt, suppressAlert, date)
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
                    readingDate = date,
                )
            }
            else -> {
                AlertNotifier.cancelAlerts(context)
                if (prevSeverity == "red") clearRedEpisodeState(prefs)
                prefs.edit { remove(KEY_YELLOW_LAST_ALERTED_PROJECTED) }
                recordAlertAction(context, date, "none")
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
     *  low-side recovery logic. (The original 2026-08-01 note here also said
     *  a misclassification would "schedule the wrong EmergencyAlertType...
     *  texting an emergency contact that the person is HIGH while they're
     *  actually crashing low." That consequence no longer exists - the whole
     *  emergency/ package and SEND_SMS went on 2026-08-20; the app has no SMS
     *  capability at all. Corrected 2026-09-20 because the sentence read as
     *  live behaviour. What misrouting still costs is real but smaller: the
     *  45-min high cooldown instead of 15, no low clear-hysteresis, and the
     *  rolling 90-min high correction grace.) Found via a real episode that scored red
     *  at value=79/projected=67 and was misclassified high-side. See
     *  AlertThresholds.kt - this now delegates to the one shared copy of the
     *  same formula that AlertNotifier also uses. */

    /** Whether [id] is currently sitting in the notification tray. Fails SAFE toward "yes":
     *  if the platform won't tell us, we must not treat that as licence to re-post. */
    private fun isNotificationPosted(context: Context, id: Int): Boolean = runCatching {
        context.getSystemService(android.app.NotificationManager::class.java)
            .activeNotifications.any { it.id == id }
    }.getOrDefault(true)

    /** Same "is this genuinely still low" question isLowSide answers (raw value OR projection
     *  under LOW_HIGH_SPLIT) - kept as its own name here since LowAlertPhase's decision is about
     *  the CURRENT episode's state, not about routing a reading to the low vs high branch. */
    private fun isStillLow(value: Int, projected: Int?): Boolean =
        value < LOW_HIGH_SPLIT || (projected != null && projected < LOW_HIGH_SPLIT)

    /** 2026-09-23: severe = at/under SeverityEngine's hard RED floor, or projected there within 15
     *  min. Found by replay: the original phase logic only looked at rate streaks, so 57 mg/dL with
     *  an easing fall got the calm STANDARD copy ("keep monitoring") and 70 mg/dL projected to 50
     *  never escalated because -1.4 never crossed the -1.5 trigger. Severity of WHERE you are/are
     *  heading now outranks how fast you got there. */
    private fun isSevere(value: Int, projected: Int?): Boolean =
        value <= SeverityEngine.SEVERE_LOW_RED_FLOOR ||
            (projected != null && projected <= SeverityEngine.SEVERE_LOW_RED_FLOOR)

    private fun derivePhase(stillLow: Boolean, severe: Boolean, rate: Double?, worseningStreak: Int): LowAlertPhase = when {
        !stillLow -> LowAlertPhase.RECOVERING
        severe -> LowAlertPhase.URGENT
        rate != null && rate > 0 -> LowAlertPhase.RISING
        worseningStreak >= LOW_WORSENING_READINGS_REQUIRED -> LowAlertPhase.URGENT
        else -> LowAlertPhase.STANDARD
    }

    /** First alert of an episode: no streak history yet, so fall back to what's knowable from one
     *  reading. 2026-09-23: used to be "rising -> RISING, anything else -> URGENT", which made a
     *  gentle -0.6 drift at 79 read "URGENT" while a real 57 on a heartbeat read "keep monitoring". */
    private fun firstAlertPhase(value: Int, projected: Int?, rate: Double?): LowAlertPhase = when {
        isSevere(value, projected) -> LowAlertPhase.URGENT
        rate != null && rate > 0 -> LowAlertPhase.RISING
        rate != null && rate <= SeverityEngine.RATE_FALLING_TRIGGER -> LowAlertPhase.URGENT
        else -> LowAlertPhase.STANDARD
    }

    /** Read-only: derives the current LowAlertPhase from whatever streak is already persisted,
     *  without advancing it. Used by the heartbeat path (see fireRedIfWarranted's mutateLowStreak
     *  doc) so a same-date re-render can still reflect a real rate change in its copy without
     *  counting as an extra "consecutive reading" toward the worsening streak. */
    private fun currentLowPhase(prefs: SharedPreferences, value: Int, projected: Int?, rate: Double?): LowAlertPhase =
        derivePhase(isStillLow(value, projected), isSevere(value, projected), rate, prefs.getInt(KEY_LOW_WORSENING_STREAK, 0))

    /**
     * The single decision point both low-side red paths (the actively-red fireRedIfWarranted,
     * and the held-past-red block in handleReading) share for LowAlertPhase, so the two can never
     * disagree about what tier a given reading is in. Advances and persists both streaks for
     * THIS reading, then derives the phase from the result - see LowAlertPhase's doc for what
     * each phase means and why the streaks exist.
     *
     * Worsening streak: consecutive readings at/below SeverityEngine.RATE_FALLING_TRIGGER while
     * still genuinely low (isStillLow) - resets to 0 the moment either condition fails. Gates the
     * URGENT phase so one noisy blip can't swing the tone.
     *
     * Stability streak: consecutive readings holding or climbing (rate >= 0, or unknown - a
     * missing rate is never treated as "still falling") once no longer low - resets the instant
     * value/projection drops back under the threshold OR the rate goes negative again, even if
     * still >= 70. That reset is what catches a bounce still in progress (see
     * LOW_STABILITY_READINGS_REQUIRED's doc) rather than treating one good tick as recovery.
     */
    private fun updateLowPhase(prefs: SharedPreferences, value: Int, projected: Int?, rate: Double?): LowAlertPhase {
        val stillLow = isStillLow(value, projected)

        val worsening = stillLow && rate != null && rate <= SeverityEngine.RATE_FALLING_TRIGGER
        val worseningStreak = if (worsening) prefs.getInt(KEY_LOW_WORSENING_STREAK, 0) + 1 else 0

        val holdingOrClimbing = rate == null || rate >= 0
        val stabilityStreak = if (!stillLow && holdingOrClimbing) prefs.getInt(KEY_LOW_STABILITY_STREAK, 0) + 1 else 0

        prefs.edit {
            putInt(KEY_LOW_WORSENING_STREAK, worseningStreak)
            putInt(KEY_LOW_STABILITY_STREAK, stabilityStreak)
        }

        return derivePhase(stillLow, isSevere(value, projected), rate, worseningStreak)
    }

    /** Wipes per-episode state when a red episode ends, so the next one
     *  (low or high) starts from a clean slate instead of inheriting stale
     *  state from an unrelated earlier episode. */
    private fun clearRedEpisodeState(prefs: SharedPreferences) {
        prefs.edit {
            remove(KEY_LOW_WAS_RECOVERING)
            remove(KEY_LOW_WAS_HELD)
            remove(KEY_RED_LOW_SIDE)
            remove(KEY_HIGH_RED_HELD_SINCE)
            remove(KEY_LOW_WORSENING_STREAK)
            remove(KEY_LOW_STABILITY_STREAK)
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
        readingDate: Long = 0L,
    ) {
        // "Entering red" (structural bookkeeping - reseed the side-tracking
        // state that clearRedEpisodeState wipes on any red->yellow downgrade)
        // is not the same question as "should this force-fire immediately"
        // (see the class doc's item 1) - a yellow->red flap re-enters red
        // structurally but is NOT a fresh episode on the high side.
        val enteringRed = prevSeverity != "red"
        // Read BEFORE the enteringRed reset below (and before fireRedIfWarranted's own
        // updateLowPhase call resets it further down) - see forceFire's doc just below for why
        // this matters: prevSeverity stays "red" in prefs for the ENTIRE duration a low episode
        // is held past its actual severity drop (see handleReading's hold block), so enteringRed
        // alone can never detect "this was recovering and just reversed."
        val wasRecoveringNotYetStable = prefs.getInt(KEY_LOW_STABILITY_STREAK, 0) > 0
        if (enteringRed) {
            prefs.edit {
                // Remember which side this episode is, so the clear-hysteresis
                // below only ever holds a LOW red (never a high one).
                putBoolean(KEY_RED_LOW_SIDE, isLowSide(reading.value, reading.projected))
                remove(KEY_HIGH_RED_HELD_SINCE)
                // A genuinely fresh episode starts its urgency/stability streaks clean too -
                // defense in depth alongside clearRedEpisodeState (which should have already
                // cleared these when the PREVIOUS episode ended).
                remove(KEY_LOW_WORSENING_STREAK)
                remove(KEY_LOW_STABILITY_STREAK)
            }
        } else {
            prefs.edit { remove(KEY_HIGH_RED_HELD_SINCE) }
        }
        val forceFire = if (isLowSide(reading.value, reading.projected)) {
            // A reading that had built ANY stability streak (KEY_LAST_SEVERITY stuck on "red" in
            // prefs the whole time it was held - see the comment above) and is now genuinely
            // scored red again is a reversal, not a continuation - same urgency as a fresh
            // episode, per the ticket's "a bounce that hadn't finished" case (real values:
            // 79 -> 87 -> 89 -> 83 @ -1.2). Without this, fireRedIfWarranted's ordinary 15-minute
            // cooldown could silently swallow exactly the re-alert this ticket asked for.
            enteringRed || wasRecoveringNotYetStable
        } else {
            // High side only: a yellow->red flap is the person never actually
            // getting out of the high, not new information - fall through to
            // the ordinary cooldown/grace path in fireRedIfWarranted instead
            // of bypassing it. Only a genuinely fresh episode (was in-range)
            // force-fires.
            prevSeverity == "none"
        }
        fireRedIfWarranted(context, prefs, reading, forceFire, now, lastRedFiredAt, suppressAlert, readingDate)
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
        fireRedIfWarranted(context, prefs, reading, forceFire = false, now, lastRedFiredAt, suppressAlert, mutateLowStreak = false)
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
        readingDate: Long = 0L,
        // False only from handleRedHeartbeat's 60s-tick re-emission (same backend trend.date as
        // last handled): the worsening/stability streaks must only advance on a genuinely NEW
        // reading (handleRedTransition), never on a same-date re-render - otherwise a single real
        // CGM sample could satisfy "2 consecutive readings" within a couple of ticks/minutes
        // just by being re-evaluated repeatedly, defeating the whole point of the streak (see
        // LowAlertPhase's doc). A heartbeat still reads the CURRENT streak/rate to pick a phase
        // for copy purposes - it just doesn't get to advance it.
        mutateLowStreak: Boolean = true,
    ) {
        val value = reading.value
        val rate = reading.ratePerMinute

        if (isLowSide(value, reading.projected)) {
            // Always advance/read the streak (a later heartbeat or held cycle needs a real
            // baseline) - but a brand-new episode (forceFire) has no history yet to judge
            // "sustained" against, so its OWN alert copy uses firstAlertPhase (severity of where
            // you are/are heading, plus a fast rate) instead of the streak-gated split.
            val streakPhase = if (mutateLowStreak) {
                updateLowPhase(prefs, value, reading.projected, rate)
            } else {
                currentLowPhase(prefs, value, reading.projected, rate)
            }
            val phase = if (forceFire) {
                firstAlertPhase(value, reading.projected, rate)
            } else {
                streakPhase
            }
            // Suppression/"recovery stalled" tracking stays RATE-based, not copy-based: a 58 that's
            // rising reads URGENT (severe floor) but is still genuinely recovering, and must keep
            // the instant re-fire if that recovery stalls. (Caught by the existing
            // recovery-stall test when these were briefly coupled on 2026-09-23.)
            val recovering = (rate != null && rate > 0) || phase == LowAlertPhase.RECOVERING

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
                recordAlertAction(context, readingDate, "held_low_red")
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
                AlertNotifier.showRedAlert(context, value, reading.projected, rate, lowPhase = phase, projectedExtended = reading.projectedExtended)
                prefs.edit { putLong(KEY_LAST_RED_FIRED_AT, now) }
                recordAlertAction(context, readingDate, "audible_red")
            } else {
                recordAlertAction(context, readingDate, "suppressed_cooldown")
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
            recordAlertAction(context, readingDate, "suppressed_cooldown")
            return
        }

        if ((forceFire || now - lastRedFiredAt >= RED_HIGH_REALERT_COOLDOWN_MS) && !suppressAlert) {
            // No lowPhase passed - high side, defaults to URGENT (unaffected by LowAlertPhase).
            AlertNotifier.showRedAlert(context, value, reading.projected, rate, projectedExtended = reading.projectedExtended)
            prefs.edit { putLong(KEY_LAST_RED_FIRED_AT, now) }
            recordAlertAction(context, readingDate, "audible_red")
        } else {
            recordAlertAction(context, readingDate, "suppressed_cooldown")
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
        readingDate: Long = 0L,
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
            // 2026-09-23: a downgrade from red must still leave SOMETHING visible, even muted -
            // AlertCoordinator's caller already cancelled the red notification right before this
            // call (see handleReading's yellow branch). Without this, a low that clears the new
            // (shorter, streak-based) stability buffer right as it enters this grace window would
            // go from a posted red alert to nothing at all - the exact "notification the
            // hysteresis assumes is still there" failure class the rest of this file exists to
            // avoid (see AlertNotifier.showRedAlert's own isNotificationPosted-adjacent doc).
            if (downgradedFromRed && !suppressAlert) {
                AlertNotifier.showYellowAlert(
                    context, reading.value, reading.projected, reading.ratePerMinute,
                    projectedExtended = reading.projectedExtended,
                    silent = true,
                )
            }
            if (projected != null) prefs.edit { putInt(KEY_YELLOW_LAST_ALERTED_PROJECTED, projected) }
            recordAlertAction(context, readingDate, "suppressed_cooldown")
            return
        }

        if (forceFire) {
            val isHighSide = (reading.projected ?: reading.value) >= YELLOW_MID_POINT
            val isFastRise = reading.ratePerMinute != null && reading.ratePerMinute >= 1.5
            val isEscalatedHigh = (reading.projected ?: reading.value) >= 240 || reading.value >= 240
            // Any downgrade from red on the high side is an improvement or leveling off from a
            // prior critical alert. Never blast an audible tone or TTS speech on a high-side downgrade!
            val shouldAudiblyAlert = if (downgradedFromRed && isHighSide) {
                false
            } else {
                !isHighSide || isFastRise || isEscalatedHigh
            }

            // Minimum gap between audible yellows - see MIN_YELLOW_REALERT_GAP_MS. Without it a
            // value wobbling across the boundary re-entered yellow (and so force-fired) every
            // single cycle. The state bookkeeping below still runs either way, so a genuinely
            // worsening episode is never mis-tracked, it just doesn't re-interrupt.
            val floorCleared = now - prefs.getLong(KEY_LAST_YELLOW_FIRED_AT, 0L) >= MIN_YELLOW_REALERT_GAP_MS
            if (!suppressAlert) {
                if (shouldAudiblyAlert && floorCleared) {
                    AlertNotifier.showYellowAlert(
                        context, reading.value, reading.projected, reading.ratePerMinute,
                        projectedExtended = reading.projectedExtended,
                        silent = false,
                    )
                    prefs.edit { putLong(KEY_LAST_YELLOW_FIRED_AT, now) }
                    recordAlertAction(context, readingDate, "audible_yellow")
                } else if (downgradedFromRed && isHighSide) {
                    // Update tray notification quietly on downgrade from red so the status bar and drawer stay accurate without audio/TTS fatigue
                    AlertNotifier.showYellowAlert(
                        context, reading.value, reading.projected, reading.ratePerMinute,
                        projectedExtended = reading.projectedExtended,
                        silent = true,
                    )
                    recordAlertAction(context, readingDate, "silent_yellow_update")
                } else {
                    recordAlertAction(context, readingDate, "suppressed_cooldown")
                }
            } else {
                recordAlertAction(context, readingDate, "suppressed_cooldown")
            }
            if (projected != null) prefs.edit { putInt(KEY_YELLOW_LAST_ALERTED_PROJECTED, projected) }
            return
        }

        if (projected == null || suppressAlert) {
            recordAlertAction(context, readingDate, "none")
            return
        }

        val lastAlertedProjected = prefs.getInt(KEY_YELLOW_LAST_ALERTED_PROJECTED, projected)
        val isLowSide = projected < YELLOW_MID_POINT
        val worsenedBy = if (isLowSide) lastAlertedProjected - projected else projected - lastAlertedProjected

        if (worsenedBy >= YELLOW_MATERIAL_WORSENING_MGDL) {
            // Deliberately NOT gated by MIN_YELLOW_REALERT_GAP_MS: the projection moving 20+
            // further into danger is real new information, not a flap.
            AlertNotifier.showYellowAlert(context, reading.value, reading.projected, reading.ratePerMinute, projectedExtended = reading.projectedExtended)
            prefs.edit {
                putInt(KEY_YELLOW_LAST_ALERTED_PROJECTED, projected)
                putLong(KEY_LAST_YELLOW_FIRED_AT, now)
            }
            recordAlertAction(context, readingDate, "audible_yellow")
        } else {
            recordAlertAction(context, readingDate, "suppressed_cooldown")
        }
    }

    private fun recordAlertAction(context: Context, readingTimeMs: Long, action: String) {
        if (readingTimeMs > 0L) {
            runCatching {
                com.aheadt1d.app.network.BackendClient.postAlertAction(context, readingTimeMs, action)
            }
        }
    }
}
