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
 */
object PlateauCoordinator {
    private const val PREFS_NAME = "ahead_plateau_state"
    private const val KEY_PLATEAU_ACTIVE = "plateau_active"
    private const val KEY_PLATEAU_LAST_TIER = "plateau_last_tier"
    private const val KEY_PLATEAU_LAST_FIRED_AT = "plateau_last_fired_at_ms"
    private const val KEY_CORRECTION_LOGGED_AT = "correction_logged_at_ms"
    private const val KEY_CORRECTION_WINDOW_NOTIFIED = "correction_window_notified"

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
        val existingLoggedAt = prefs.getLong(KEY_CORRECTION_LOGGED_AT, 0L)
        val windowStillOpen = existingLoggedAt != 0L &&
            (timestamp - existingLoggedAt) < tuning.correctionWindowMinutes * 60_000L
        val currentValue = LatestTrendRepository.latestRawReading.value?.value
        val stillElevated = currentValue != null && currentValue >= tuning.highThreshold

        if (windowStillOpen && stillElevated) {
            // Purely informational/awareness per spec - no dosing guidance,
            // no judgment language. Leaves the ORIGINAL window's anchor
            // untouched: "while the first CORRECTION_WINDOW is still open."
            if (!CheckNowSuppression.isSuppressed()) {
                val minutesSinceFirst = (timestamp - existingLoggedAt) / 60_000L
                AlertNotifier.showRepeatCorrectionAlert(context, minutesSinceFirst)
            }
            return
        }

        // Either no window was open, the prior one has aged out past
        // correctionWindowMinutes, or glucose already isn't elevated
        // (nothing meaningful to compare a repeat correction against) -
        // either way, this correction starts a fresh tracking window.
        prefs.edit {
            putLong(KEY_CORRECTION_LOGGED_AT, timestamp)
            putBoolean(KEY_CORRECTION_WINDOW_NOTIFIED, false)
        }
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
        val loggedAt = prefs.getLong(KEY_CORRECTION_LOGGED_AT, 0L)
        if (loggedAt == 0L) return
        if (prefs.getBoolean(KEY_CORRECTION_WINDOW_NOTIFIED, false)) return

        val currentValue = points.maxByOrNull { it.time }?.sgv
        val currentRate = HealthConnectManager.calculateRatePerMinute(points)

        when (
            CorrectionResponseMath.evaluate(
                correctionLoggedAt = loggedAt,
                now = System.currentTimeMillis(),
                windowMinutes = tuning.correctionWindowMinutes.toLong(),
                currentValue = currentValue,
                currentRatePerMinute = currentRate,
                highThreshold = tuning.highThreshold,
                responseRateThreshold = tuning.correctionResponseRateThreshold,
            )
        ) {
            CorrectionResponseMath.Outcome.WINDOW_OPEN -> Unit // still waiting
            CorrectionResponseMath.Outcome.RESPONDING_OR_RESOLVED -> {
                // Worked (or resolved on its own) - stop tracking silently,
                // no notification needed. Also clears the anchor so a later
                // correction doesn't get misread as "repeat, still elevated"
                // against an episode that's already resolved.
                prefs.edit {
                    remove(KEY_CORRECTION_LOGGED_AT)
                    remove(KEY_CORRECTION_WINDOW_NOTIFIED)
                }
            }
            CorrectionResponseMath.Outcome.NOT_RESPONDING -> {
                if (currentValue != null && !CheckNowSuppression.isSuppressed()) {
                    val minutesSinceCorrection = (System.currentTimeMillis() - loggedAt) / 60_000L
                    val plateauActive = prefs.getBoolean(KEY_PLATEAU_ACTIVE, false)
                    AlertNotifier.showCorrectionNotRespondingAlert(context, currentValue, minutesSinceCorrection, plateauActive)
                }
                // Flag as notified but deliberately keep KEY_CORRECTION_LOGGED_AT -
                // a correction logged shortly after this should still read as
                // "repeat correction, close together" against the same episode.
                prefs.edit { putBoolean(KEY_CORRECTION_WINDOW_NOTIFIED, true) }
            }
        }
    }
}
