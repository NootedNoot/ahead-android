package com.aheadt1d.app.notifications

import android.content.Context
import com.aheadt1d.app.state.LatestTrend
import com.aheadt1d.app.state.RawReading
import com.aheadt1d.app.state.ReadBlockedReason
import com.aheadt1d.app.state.TREND_MATCH_TOLERANCE_MS
import com.aheadt1d.app.state.effectiveRatePerMinute
import com.aheadt1d.app.state.isStale
import com.aheadt1d.app.state.minutesSinceReading
import org.aheadt1d.ratemath.SeverityEngine
import kotlin.math.abs

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
        // Backend-scored when the latest trend is current (same tolerance gate
        // as the arrow-rate fallback); when it's too old to trust, falls back
        // to a local, client-side projection instead of going null - see
        // toDisplayState below. The local fallback only ever
        // yields "yellow", never "red" - a stale backend "red" would be worse
        // than none, but a stale/noisy local "red" would be worse still (a
        // false-positive full-screen takeover).
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
    // stale/dedup'd its direction could be hours out of date.
    // When neither source has a rate, null → fromRatePerMinute → FLAT.
    // Rate comes from the shared effectiveRatePerMinute() - the same
    // function MainActivity displays from, so the notification and the
    // main screen can never disagree about the rate for one check cycle.
    // The same tolerance gate covers severity/projection: only trust
    // backend fields scored around this same reading.
    val trendIsCurrent = trend != null && abs(trend.date - raw.time) <= TREND_MATCH_TOLERANCE_MS

    val rate = effectiveRatePerMinute(raw, trend)

    // Local, client-side 15-min-ahead projection from this poll's own
    // raw value/rate - independent of the backend. Used only as a
    val decision = SeverityEngine.classify(
        currentValue = raw.value,
        ratePerMinute = rate,
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
