package com.aheadt1d.app.alerts

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.aheadt1d.app.health.GlucosePoint
import com.aheadt1d.app.health.HealthConnectManager
import com.aheadt1d.app.state.LatestTrendRepository
import com.aheadt1d.app.tuning.PlateauTuningParameters
import com.aheadt1d.app.tuning.PlateauTuningPrefs

/**
 * Decides *when* the sustained-high-plateau (Gap 1) and correction-response
 * (Gap 2) alerts fire, mirroring AlertCoordinator's shape (SharedPreferences-
 * backed, @Synchronized evaluate()) but as a fully independent state
 * machine. These are independent signals from the rate-of-change engine
 * (PlateauMath/CorrectionResponseMath do the actual math) - this object
 * never reads or writes AlertCoordinator's state, and vice versa, so a
 * plateau alert and a rate-based red/yellow alert can be active at the same
 * time without either suppressing the other.
 *
 * ONE deliberate, narrow exception to that boundary (2026-08-26, at the
 * owner's request): [activeLowCorrectionAnchorMs]/[activeHighCorrectionAnchorMs]
 * are READ-ONLY accessors AlertCoordinator uses to hold off a re-alert for a
 * while after a correction is logged, on the theory that a human already
 * intervened and the ordinary alert doesn't need to repeat itself while that
 * intervention is still plausibly working. AlertCoordinator never writes here,
 * and this object's own escalation logic is completely unaffected by who
 * reads them - see AlertCoordinator's LOW_CORRECTION_GRACE_MS/
 * HIGH_CORRECTION_GRACE_MS for the asymmetric reasoning (a low needs to
 * resolve fast, a high can be legitimately managed over hours with several
 * corrections).
 */
object PlateauCoordinator {
    private const val PREFS_NAME = "ahead_plateau_state"
    private const val KEY_PLATEAU_ACTIVE = "plateau_active"
    private const val KEY_PLATEAU_LAST_TIER = "plateau_last_tier"
    private const val KEY_PLATEAU_LAST_FIRED_AT = "plateau_last_fired_at_ms"
    private const val KEY_CORRECTION_LOGGED_AT = "correction_logged_at_ms"
    private const val KEY_CORRECTION_WINDOW_NOTIFIED = "correction_window_notified"
    private const val KEY_CORRECTION_DIRECTION = "correction_direction"
    // Separate from KEY_CORRECTION_LOGGED_AT above on purpose: that field is
    // the FIXED anchor of the current plateau-escalation window (deliberately
    // untouched by a repeat correction while the window is still open - see
    // onCorrectionLogged). This pair is the opposite: it ALWAYS updates to the
    // most recent correction, of either direction, so AlertCoordinator's
    // rolling high-side grace window (see activeHighCorrectionAnchorMs) can
    // keep extending across "several corrections... hours" the way a real
    // multi-dose high-correction episode actually plays out. The low side
    // deliberately does NOT use this rolling field - see
    // activeLowCorrectionAnchorMs.
    private const val KEY_LAST_CORRECTION_AT = "last_correction_at_ms"
    private const val KEY_LAST_CORRECTION_DIRECTION = "last_correction_direction"
    private const val DIRECTION_LOW = "low"
    private const val DIRECTION_HIGH = "high"

    // --- Correction-tracking state, as two named types instead of raw keys
    // (consolidated 2026-08-26) -------------------------------------------
    //
    // One logical event ("a correction was logged") used to be five loose
    // SharedPreferences keys read/written by hand throughout this file, with
    // the fixed-vs-rolling distinction documented only in comments. These two
    // small types make that distinction the actual shape of the code.

    /** The FIXED-anchor tracking window for the Gap-2 correction-response
     *  escalation (see [handleCorrectionResponse]) - direction-specific,
     *  deliberately NOT extended by a repeat correction while still open
     *  (see [onCorrectionLogged]). Also the anchor
     *  [activeLowCorrectionAnchorMs] hands to AlertCoordinator's low-side
     *  grace: fixed to the FIRST correction, matching the low side's short,
     *  non-extending ceiling. */
    private data class CorrectionWindow(val loggedAt: Long, val direction: String, val notified: Boolean)

    private fun readWindow(prefs: SharedPreferences): CorrectionWindow? {
        val loggedAt = prefs.getLong(KEY_CORRECTION_LOGGED_AT, 0L)
        if (loggedAt == 0L) return null
        return CorrectionWindow(
            loggedAt = loggedAt,
            direction = prefs.getString(KEY_CORRECTION_DIRECTION, DIRECTION_HIGH) ?: DIRECTION_HIGH,
            notified = prefs.getBoolean(KEY_CORRECTION_WINDOW_NOTIFIED, false),
        )
    }

    private fun writeWindow(prefs: SharedPreferences, window: CorrectionWindow) {
        prefs.edit {
            putLong(KEY_CORRECTION_LOGGED_AT, window.loggedAt)
            putString(KEY_CORRECTION_DIRECTION, window.direction)
            putBoolean(KEY_CORRECTION_WINDOW_NOTIFIED, window.notified)
        }
    }

    private fun clearWindow(prefs: SharedPreferences) {
        prefs.edit {
            remove(KEY_CORRECTION_LOGGED_AT)
            remove(KEY_CORRECTION_WINDOW_NOTIFIED)
            remove(KEY_CORRECTION_DIRECTION)
        }
    }

    /** The ROLLING most-recent-correction marker - always overwritten on
     *  every correction log, of EITHER direction, unlike [CorrectionWindow]
     *  above. [activeHighCorrectionAnchorMs] hands this to AlertCoordinator's
     *  high-side grace, which is why it needs to keep moving forward across
     *  "several corrections... hours" the way a real multi-dose high-
     *  correction episode actually plays out - the fixed window above can't
     *  serve that purpose since it deliberately doesn't move. */
    private data class LastCorrection(val at: Long, val direction: String)

    private fun readLastCorrection(prefs: SharedPreferences): LastCorrection? {
        val at = prefs.getLong(KEY_LAST_CORRECTION_AT, 0L)
        if (at == 0L) return null
        return LastCorrection(at, prefs.getString(KEY_LAST_CORRECTION_DIRECTION, DIRECTION_HIGH) ?: DIRECTION_HIGH)
    }

    private fun writeLastCorrection(prefs: SharedPreferences, correction: LastCorrection) {
        prefs.edit {
            putLong(KEY_LAST_CORRECTION_AT, correction.at)
            putString(KEY_LAST_CORRECTION_DIRECTION, correction.direction)
        }
    }

    private fun clearLastCorrection(prefs: SharedPreferences) {
        prefs.edit {
            remove(KEY_LAST_CORRECTION_AT)
            remove(KEY_LAST_CORRECTION_DIRECTION)
        }
    }

    /** Synchronized: same reasoning as AlertCoordinator.evaluate - the
     *  read-decide-persist sequence must be atomic so two concurrent callers
     *  can't both fire for the same tier crossing. */
    @Synchronized
    fun evaluate(
        context: Context,
        points: List<GlucosePoint>,
        tuning: PlateauTuningParameters = PlateauTuningPrefs.load(context),
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        handlePlateau(context, prefs, points, tuning)
        handleCorrectionResponse(context, prefs, points, tuning)
    }

    /**
     * Called when an EventTag.CORRECTION event is logged (see
     * EventLogDialogs.logAndConfirm's hook). [timestamp] matches whatever the
     * event itself was stamped with - "now" for a normal log, or a historical
     * time if logged via GraphActivity's backdated chart-point flow, so
     * tracking always starts from when the correction actually happened, not
     * necessarily when this function runs.
     *
     * Direction (high vs low) is inferred from glucose AT LOGGING TIME, not
     * stored per-event - a "Correction" tag means insulin above highThreshold
     * and fast carbs below lowThreshold, and the two can never overlap since
     * lowThreshold < highThreshold. Logging while in-range (neither) starts
     * no window - there's nothing to check a response against.
     *
     * @Synchronized for the same reason as evaluate() - both read-decide-
     * write against the same prefs file, and this can run concurrently with
     * a Worker-triggered evaluate() call (a user can tap "log correction"
     * at any moment).
     */
    @Synchronized
    fun onCorrectionLogged(
        context: Context,
        timestamp: Long = System.currentTimeMillis(),
        tuning: PlateauTuningParameters = PlateauTuningPrefs.load(context),
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentValue = LatestTrendRepository.latestRawReading.value?.value
        val isLowNow = currentValue != null && currentValue <= tuning.lowThreshold
        val isHighNow = currentValue != null && currentValue >= tuning.highThreshold

        // Always stamp the rolling last-correction marker, regardless of
        // whether this is a fresh window or a repeat within an open one -
        // unlike the fixed window below, this one is meant to keep moving
        // forward with every real intervention. See LastCorrection's doc.
        val rollingDirection = when {
            isLowNow -> DIRECTION_LOW
            isHighNow -> DIRECTION_HIGH
            else -> null
        }
        if (rollingDirection != null) {
            writeLastCorrection(prefs, LastCorrection(at = timestamp, direction = rollingDirection))
        }

        val existingWindow = readWindow(prefs)
        val windowMinutes = if (existingWindow?.direction == DIRECTION_LOW) {
            tuning.lowCorrectionWindowMinutes
        } else {
            tuning.correctionWindowMinutes
        }
        val windowStillOpen = existingWindow != null &&
            (timestamp - existingWindow.loggedAt) < windowMinutes * 60_000L
        // "Still matching" means glucose is currently on the SAME side the
        // open window is tracking - a low-window correction repeated while
        // now merely high (or vice versa) isn't a repeat of the same episode.
        val stillMatchingDirection = when (existingWindow?.direction) {
            DIRECTION_LOW -> isLowNow
            DIRECTION_HIGH -> isHighNow
            else -> false
        }

        if (windowStillOpen && stillMatchingDirection && existingWindow != null) {
            // Purely informational/awareness per spec - no dosing guidance,
            // no judgment language. Leaves the ORIGINAL window's anchor
            // untouched: "while the first CORRECTION_WINDOW is still open."
            if (!CheckNowSuppression.isSuppressed()) {
                val minutesSinceFirst = (timestamp - existingWindow.loggedAt) / 60_000L
                AlertNotifier.showRepeatCorrectionAlert(context, minutesSinceFirst, isLow = existingWindow.direction == DIRECTION_LOW)
            }
            return
        }

        // Either no window was open, the prior one has aged out, or glucose
        // is no longer on the same side as the open window - either way,
        // this correction starts a fresh tracking window, in whichever
        // direction glucose is in RIGHT NOW. In-range (neither low nor high)
        // opens nothing - there's no elevated/low episode to compare against.
        val newDirection = when {
            isLowNow -> DIRECTION_LOW
            isHighNow -> DIRECTION_HIGH
            else -> return
        }
        writeWindow(prefs, CorrectionWindow(loggedAt = timestamp, direction = newDirection, notified = false))
    }

    /** Fixed, non-extending anchor for AlertCoordinator's low-side correction
     *  grace: null unless there's a currently-open LOW correction window, in
     *  which case it's when that window's FIRST correction was logged -
     *  repeat low corrections don't push this forward, matching the low
     *  side's short, fixed grace ceiling. */
    fun activeLowCorrectionAnchorMs(context: Context): Long? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return readWindow(prefs)?.takeIf { it.direction == DIRECTION_LOW }?.loggedAt
    }

    /** Rolling anchor for AlertCoordinator's high-side correction grace: null
     *  unless the MOST RECENT correction of any kind was a HIGH correction, in
     *  which case it's when that one was logged - this keeps moving forward
     *  with every additional high correction, matching the high side's
     *  "managed over hours, several doses" ceiling. */
    fun activeHighCorrectionAnchorMs(context: Context): Long? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return readLastCorrection(prefs)?.takeIf { it.direction == DIRECTION_HIGH }?.at
    }

    private fun handlePlateau(
        context: Context,
        prefs: SharedPreferences,
        points: List<GlucosePoint>,
        tuning: PlateauTuningParameters,
    ) {
        val duration = PlateauMath.currentPlateauDurationMinutes(points, tuning.highThreshold)
        val wasActive = prefs.getBoolean(KEY_PLATEAU_ACTIVE, false)

        if (duration == null) {
            // The latest reading itself is below HIGH_THRESHOLD. Only clear
            // an active episode once it's also past the hysteresis floor -
            // a value merely dipping under the plain threshold (but still
            // above threshold-buffer) holds its alert state rather than
            // flapping clear/re-fire right at the boundary.
            if (wasActive && PlateauMath.hasDroppedBelowHysteresisFloor(points, tuning.highThreshold, tuning.hysteresisBuffer)) {
                clearPlateau(context, prefs)
            }
            return
        }

        val tier = PlateauMath.tierFor(duration, tuning.highDurationMinutes.toLong(), tuning.escalationStepMinutes.toLong())
        if (tier == 0) {
            // Still above threshold but hasn't reached HIGH_DURATION yet -
            // not a plateau, nothing to fire or hold.
            return
        }

        val latestValue = points.maxByOrNull { it.time }?.sgv ?: return
        val lastTier = prefs.getInt(KEY_PLATEAU_LAST_TIER, 0)
        val lastFiredAt = prefs.getLong(KEY_PLATEAU_LAST_FIRED_AT, 0L)
        val now = System.currentTimeMillis()
        val cooldownMs = tuning.cooldownMinutes * 60_000L

        val shouldFire = when {
            !wasActive -> true                       // brand new episode - always announce
            tier > lastTier -> true                   // escalated to the next severity tier
            now - lastFiredAt >= cooldownMs -> true    // still active, cooldown elapsed
            else -> false                              // holding - already know about this one
        }

        if (shouldFire) {
            if (!CheckNowSuppression.isSuppressed()) {
                AlertNotifier.showPlateauAlert(context, latestValue, duration, tier, tuning.highThreshold, tuning.highDurationMinutes)
            }
            prefs.edit {
                putBoolean(KEY_PLATEAU_ACTIVE, true)
                putInt(KEY_PLATEAU_LAST_TIER, tier)
                putLong(KEY_PLATEAU_LAST_FIRED_AT, now)
            }
        }
    }

    private fun clearPlateau(context: Context, prefs: SharedPreferences) {
        prefs.edit {
            putBoolean(KEY_PLATEAU_ACTIVE, false)
            remove(KEY_PLATEAU_LAST_TIER)
            remove(KEY_PLATEAU_LAST_FIRED_AT)
        }
        AlertNotifier.cancelPlateau(context)
    }

    /** Checks whether an open correction-tracking window has elapsed without
     *  glucose responding - runs every evaluate() cycle alongside the
     *  plateau check, using the same points/tuning the Worker already
     *  fetched. No-ops instantly (single getLong) when nothing is being
     *  tracked, which is the common case. */
    private fun handleCorrectionResponse(
        context: Context,
        prefs: SharedPreferences,
        points: List<GlucosePoint>,
        tuning: PlateauTuningParameters,
    ) {
        val window = readWindow(prefs) ?: return
        if (window.notified) return
        val loggedAt = window.loggedAt
        val isLow = window.direction == DIRECTION_LOW

        val currentValue = points.maxByOrNull { it.time }?.sgv
        val currentRate = HealthConnectManager.calculateRatePerMinute(points)

        val outcome = if (isLow) {
            CorrectionResponseMath.evaluateLow(
                correctionLoggedAt = loggedAt,
                now = System.currentTimeMillis(),
                windowMinutes = tuning.lowCorrectionWindowMinutes.toLong(),
                currentValue = currentValue,
                currentRatePerMinute = currentRate,
                lowThreshold = tuning.lowThreshold,
                responseRateThreshold = tuning.lowResponseRateThreshold,
            )
        } else {
            CorrectionResponseMath.evaluate(
                correctionLoggedAt = loggedAt,
                now = System.currentTimeMillis(),
                windowMinutes = tuning.correctionWindowMinutes.toLong(),
                currentValue = currentValue,
                currentRatePerMinute = currentRate,
                highThreshold = tuning.highThreshold,
                responseRateThreshold = tuning.correctionResponseRateThreshold,
            )
        }

        when (outcome) {
            CorrectionResponseMath.Outcome.WINDOW_OPEN -> Unit // still waiting
            // No usable reading this cycle (e.g. a transient Health Connect
            // gap) - keep the tracking window open rather than reading this
            // as a resolution. A prolonged real blackout is already handled
            // separately by AlertCoordinator's stale/signal-lost alert, so
            // there's no need to cap how long this waits.
            CorrectionResponseMath.Outcome.INCONCLUSIVE -> Unit
            CorrectionResponseMath.Outcome.RESPONDING_OR_RESOLVED -> {
                // Worked (or resolved on its own) - stop tracking silently,
                // no notification needed. Also clears the anchor so a later
                // correction doesn't get misread as "repeat, still
                // elevated/low" against an episode that's already resolved.
                clearWindow(prefs)
                // Resolved means back in range and responding - no reason for
                // AlertCoordinator's rolling grace to keep holding.
                clearLastCorrection(prefs)
            }
            CorrectionResponseMath.Outcome.NOT_RESPONDING -> {
                if (currentValue != null && !CheckNowSuppression.isSuppressed()) {
                    val minutesSinceCorrection = (System.currentTimeMillis() - loggedAt) / 60_000L
                    // Plateau (Gap 1) is high-only, so this is only ever
                    // meaningful to attach on the high-side path.
                    val plateauActive = !isLow && prefs.getBoolean(KEY_PLATEAU_ACTIVE, false)
                    AlertNotifier.showCorrectionNotRespondingAlert(context, currentValue, minutesSinceCorrection, plateauActive, isLow = isLow)
                }
                // Flag as notified but deliberately keep the window's anchor -
                // a correction logged shortly after this should still read as
                // "repeat correction, close together" against the same episode.
                writeWindow(prefs, window.copy(notified = true))
            }
        }
    }
}
