package com.aheadt1d.app.debug

import android.content.Context
import android.util.Log
import com.aheadt1d.app.alerts.AlertCoordinator
import com.aheadt1d.app.notifications.GlucoseDisplayState
import com.aheadt1d.app.notifications.GlucoseStatusService
import com.aheadt1d.app.notifications.GlucoseTrendArrow
import com.aheadt1d.app.state.LatestTrend
import com.aheadt1d.app.state.LatestTrendRepository
import com.aheadt1d.app.state.RawReading
import com.aheadt1d.app.state.isStale
import com.aheadt1d.app.state.withComputedCauseTier
import kotlinx.coroutines.runBlocking
import org.aheadt1d.ratemath.CauseTier

/**
 * Shared debug-only path for pushing a synthetic reading through the real
 * alert chain (repo -> AlertCoordinator -> notification). Used by both
 * DebugTrendInjector (adb broadcast) and DebugMenuActivity (in-app UI) so
 * there's exactly one place that knows how to fake a reading, not two
 * copies that can drift.
 */
object DebugInjection {
    private const val TAG = "DebugInjection"

    fun apply(
        context: Context,
        severity: String,
        value: Int,
        projected: Int?,
        projectedExtended: Int?,
        rate: Double,
        ageMin: Int = 0,
        delta: Int? = -8,
        causeTier: CauseTier? = null,
    ) {
        val ctx = context.applicationContext
        val readingTime = System.currentTimeMillis() - ageMin * 60_000L
        Log.d(TAG, "apply: severity=$severity value=$value projected=$projected rate=$rate ageMin=$ageMin causeTier=$causeTier")

        val trend = LatestTrend(
            currentValue = value,
            severity = severity,
            rate = rate,
            projected = projected,
            projectedExtended = projectedExtended,
            date = readingTime
        )
        val raw = RawReading(value = value, time = readingTime, ratePerMinute = rate, deltaFromPrevious = delta)
        val tieredReading = if (causeTier != null) {
            raw.copy(causeTier = causeTier)
        } else {
            runBlocking { raw.withComputedCauseTier(ctx, readingTime) }
        }
        LatestTrendRepository.updateRawReading(ctx, tieredReading)
        LatestTrendRepository.update(ctx, trend)

        val arrow = GlucoseTrendArrow.fromRatePerMinute(rate)
        val ageMinutes = (System.currentTimeMillis() - readingTime) / 60_000
        val state = if (isStale(ctx, readingTime)) {
            GlucoseDisplayState.Stale(
                lastValue = value,
                lastReadingTime = readingTime,
                ageMinutes = ageMinutes,
                lastArrow = arrow
            )
        } else {
            GlucoseDisplayState.Reading(
                value = value,
                arrow = arrow,
                readingTime = readingTime,
                deltaFromPrevious = delta,
                trendIsComputed = true,
                severity = severity,
                projected = projected,
                projectedExtended = projectedExtended,
                ratePerMinute = rate,
                causeTier = tieredReading.causeTier
            )
        }
        AlertCoordinator.evaluate(ctx, state, trend)

        // Best-effort: starts the ongoing-notification service too. Harmless
        // no-op if it can't start (e.g. from a background broadcast).
        GlucoseStatusService.ensureRunning(ctx)
    }
}
