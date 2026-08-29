package com.aheadt1d.app.notifications

import android.content.Context
import com.aheadt1d.app.state.LatestTrend
import com.aheadt1d.app.state.RawReading
import com.aheadt1d.app.state.ReadBlockedReason
import com.aheadt1d.app.state.effectiveRatePerMinute
import com.aheadt1d.app.state.isStale
import com.aheadt1d.app.state.minutesSinceReading
import org.aheadt1d.ratemath.SeverityEngine

/**
 * What the ongoing notification should show right now. Kept separate from
 * LatestTrend because it folds in staleness (derived from wall-clock time,
 * not just the stored reading) and the delta-from-previous-reading, neither
 * of which belong on the persisted trend model itself.
 */
sealed class GlucoseDisplayState {
    data class Reading(
        val value: Int,
        val arrow: GlucoseTrendArrow,
        val readingTime: Long,
        val deltaFromPrevious: Int?,
        // false when there was only one Health Connect reading available (cold
        // start / first sync) and the arrow defaulted to FLAT because rate
        // can't be calculated from a single point. Callers that want to
        // distinguish a genuine flat trend from an unknown one can check this.
        val trendIsComputed: Boolean,
        // Severity ("none"|"yellow"|"red") and 15-min-ahead projected value.
        // 2026-08-28: always computed on-device via SeverityEngine.classify
        // (ahead-rate-math) now - the backend's own classifySeverity
        // (trend-detector.js) is never read here at all, so this no longer
        // depends on the backend being reachable or its scoring being fresh.
        // This is a real architectural shift from how this field used to
        // work (backend-scored-when-fresh, local-yellow-only-fallback
        // otherwise) - see AlertCoordinator's own class doc, which still
        // needs updating to match. null means SeverityEngine classified
        // this as "none" (not "couldn't be computed" - classify() always
        // returns a real answer) - AlertCoordinator's own null-check reads
        // null as "nothing to alert on."
        val severity: String?,
        val projected: Int?,
        // 30-min projection, shown next to the 15-min one in the alert text.
        val projectedExtended: Int?,
        // Rate of change in mg/dL/min (same value that drove the arrow) -
        // surfaced separately so the notification can show it explicitly
        // labeled, distinct from deltaFromPrevious which is a raw mg/dL
        // difference between the last two readings.
        val ratePerMinute: Double?
    ) : GlucoseDisplayState()

    /** A reading exists but is older than the staleness threshold. lastArrow is
     *  the direction the last known reading was moving - surfaced so the "no new
     *  data" indicator (and the signal-lost alert) can say which way it was
     *  heading when the data went dark. [blockedReason] is the runner's
     *  app-side diagnosis (revoked permission, HC missing) when one exists -
     *  null means the gap is, as far as the app can tell, upstream at the
     *  CGM/sync side, and the copy blames the sensor only in that case. */
    data class Stale(
        val lastValue: Int,
        val lastReadingTime: Long,
        val ageMinutes: Long,
        val lastArrow: GlucoseTrendArrow,
        val blockedReason: com.aheadt1d.app.state.ReadBlockedReason? = null
    ) : GlucoseDisplayState()

    /** No reading has ever been recorded (fresh install, no permissions yet, etc). */
    object NoData : GlucoseDisplayState()
}

/**
 * Builds the display state a fresh reading represents right now. Extracted
 * 2026-08-25 from GlucoseStatusService's companion object (a pure refactor,
 * no behavior change) so MainActivity can build the identical state
 * PassiveContextEngine needs without duplicating this logic or depending on
 * the alert-critical service class itself - see PassiveContextEngine's
 * wiring in MainActivity for why that boundary matters.
 */
fun toDisplayState(context: Context, raw: RawReading?, trend: LatestTrend?, blocked: ReadBlockedReason?): GlucoseDisplayState {
    if (raw == null) return GlucoseDisplayState.NoData

    // Staleness routes through the shared isStale() (state package) - the
    // same rule MainActivity and the wizard use - so the two surfaces can
    // never disagree about the boundary.
    if (isStale(context, raw)) {
        return GlucoseDisplayState.Stale(
            lastValue = raw.value,
            lastReadingTime = raw.time,
            ageMinutes = minutesSinceReading(raw) ?: 0,
            lastArrow = GlucoseTrendArrow.fromRatePerMinute(raw.ratePerMinute),
            blockedReason = blocked
        )
    }

    // Primary: rate calculated on-device from the two most recent consecutive
    // Health Connect readings. Independent of the backend - never stale from
    // dedup or network issues.
    // Fallback: backend trend rate, but only trusted when its scored timestamp
    // is close enough to this raw reading's time (within tolerance) - if it's
    // stale/dedup'd its direction could be hours out of date. This tolerance
    // check lives inside effectiveRatePerMinute() itself now (see
    // LatestTrendStore.kt) - a near-identical check used to be duplicated
    // here too (as `trendIsCurrent`) back when this function also used it to
    // decide whether to trust the backend's severity; removed 2026-08-28
    // since severity is unconditionally local now (see below) and the
    // duplicate check had gone dead without anyone noticing.
    // When neither source has a rate, null → fromRatePerMinute → FLAT.
    // Rate comes from the shared effectiveRatePerMinute() - the same
    // function MainActivity displays from, so the notification and the
    // main screen can never disagree about the rate for one check cycle.
    val rate = effectiveRatePerMinute(raw, trend)

    // Severity/projection: entirely local now, via SeverityEngine
    // (ahead-rate-math) - see the Reading.severity field doc above for why
    // this is a real architectural shift, not just a refactor.
    // recoveringFromLow/recentRates/severityRatePerMinute/
    // excursionDurationMinutes are all computed once in GlucoseCheckRunner
    // (where the reading-history window is already being read) and
    // persisted onto RawReading - see each field's own doc for why this
    // wiring was missing entirely until 2026-08-29 (recentRates especially -
    // without it, trajectory classification never engaged on-device at
    // all), and why fixing it here (not re-deriving history in this
    // function) is the right place.
    //
    // ratePerMinute below deliberately uses raw.severityRatePerMinute (the
    // RateConsensus median of three independent estimates) when available,
    // NOT `rate` (the plain 2-point number) - but only for the SEVERITY
    // decision. The DISPLAYED rate/arrow further down this function still
    // uses `rate` unchanged, so what someone sees on screen never differs
    // from what their raw sensor data says; only the invisible alert logic
    // benefits from the more robust estimate.
    val decision = SeverityEngine.classify(
        currentValue = raw.value,
        ratePerMinute = raw.severityRatePerMinute ?: rate,
        recentRates = raw.recentRates,
        recoveringFromLow = raw.recoveringFromLow,
        excursionDurationMinutes = raw.excursionDurationMinutes,
    )

    // Hard safety floor: <= 60 is always RED immediately
    val finalSeverity = if (raw.value <= 60) {
        "red"
    } else {
        decision.severity.takeIf { it != "none" }
    }

    val finalProjected = decision.projected15m
    val finalExtended = decision.projectedExtended

    return GlucoseDisplayState.Reading(
        value = raw.value,
        arrow = GlucoseTrendArrow.fromRatePerMinute(rate),
        readingTime = raw.time,
        deltaFromPrevious = raw.deltaFromPrevious,
        trendIsComputed = rate != null,
        severity = finalSeverity,
        projected = finalProjected,
        projectedExtended = finalExtended,
        ratePerMinute = rate
    )
}
