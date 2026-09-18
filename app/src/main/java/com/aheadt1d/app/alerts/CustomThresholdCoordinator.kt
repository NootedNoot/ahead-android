package com.aheadt1d.app.alerts

import android.content.Context
import android.util.Log
import com.aheadt1d.app.notifications.GlucoseDisplayState

/**
 * Decides *when* a user-defined [CustomThreshold] actually fires, mirroring
 * PlateauCoordinator's shape (SharedPreferences-backed via
 * [CustomThresholdStore], @Synchronized evaluate()) as a fully independent
 * state machine - the pure crossing/escalation decision itself lives in
 * [CustomThresholdMath], unit-testable with no Android dependency.
 *
 * Deliberately isolated from the severity engine per the feature's own spec:
 * never reads AlertCoordinator/SeverityEngine state, never influences the
 * yellow/red classification, cooldowns, or grace windows, and evaluates off
 * the same [GlucoseDisplayState.Reading] the main screen already renders -
 * so a rate threshold always fires off the exact number visible on screen,
 * never a hidden/smoothed one (see CustomThresholdMath's own doc, and the
 * SeverityEngine projection fix earlier in this same session for why that
 * distinction matters).
 *
 * Call [evaluate] from the same place AlertCoordinator.evaluate() is called
 * (GlucoseStatusService's render loop) - every check cycle, regardless of
 * whether the displayed state changed, so a threshold that's been sitting
 * crossed for a while is still correctly tracked even when nothing else
 * about the notification needs to change.
 */
object CustomThresholdCoordinator {
    private const val TAG = "CustomThresholdCoord"

    @Synchronized
    fun evaluate(context: Context, state: GlucoseDisplayState) {
        val reading = state as? GlucoseDisplayState.Reading ?: return

        // Wrapped in the store's own monitor so this whole purge-decide-save
        // cycle is atomic against a concurrent UI-thread add/delete/setEnabled
        // from CustomThresholdsActivity - see CustomThresholdStore's own doc
        // on why (its individual methods are @Synchronized against the same
        // monitor, but a multi-call sequence like this one needs the lock
        // held across all of it, not just each call).
        synchronized(CustomThresholdStore) {
            val thresholds = CustomThresholdStore.purgeExpired(context)
            if (thresholds.isEmpty()) return

            var changed = false
            val updated = thresholds.map { threshold ->
                if (!threshold.enabled) return@map threshold

                val result = CustomThresholdMath.evaluate(threshold, reading.value, reading.ratePerMinute)
                when (result.outcome) {
                    CustomThresholdMath.Outcome.FRESH_CROSS, CustomThresholdMath.Outcome.ESCALATED -> {
                        Log.i(TAG, "Custom threshold '${threshold.displayLabel()}' ${result.outcome} at metric=${result.metric}")
                        val posted = AlertNotifier.showCustomThresholdAlert(context, threshold, reading.value, reading.ratePerMinute, result.metric)
                        // Only record "fired" when a notification actually
                        // went out (POST_NOTIFICATIONS granted, dev kill
                        // switch not active) - otherwise a real crossing
                        // that nobody was told about would get silently
                        // marked as handled, and a LATER genuine escalation
                        // would be measured against a fire that never
                        // happened instead of re-attempting to notify.
                        if (posted) {
                            changed = true
                            threshold.copy(
                                currentlyCrossed = true,
                                lastFiredAtMs = System.currentTimeMillis(),
                                lastFiredAtMetric = result.metric,
                            )
                        } else {
                            threshold
                        }
                    }
                    CustomThresholdMath.Outcome.RECOVERED -> {
                        changed = true
                        // Mirrors PlateauCoordinator's own clearPlateau(),
                        // which cancels its notification on the same kind of
                        // recovery transition - otherwise a stale "crossed"
                        // notification sits in the tray after the real
                        // condition has already cleared.
                        AlertNotifier.cancelCustomThreshold(context, threshold.id)
                        threshold.copy(currentlyCrossed = false)
                    }
                    CustomThresholdMath.Outcome.NONE -> threshold
                }
            }

            if (changed) CustomThresholdStore.save(context, updated)
        }
    }
}
