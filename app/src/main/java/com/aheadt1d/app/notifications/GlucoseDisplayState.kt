package com.aheadt1d.app.notifications

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
        // GlucoseStatusService.toDisplayState. The local fallback only ever
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
     *  heading when the data went dark. */
    data class Stale(
        val lastValue: Int,
        val lastReadingTime: Long,
        val ageMinutes: Long,
        val lastArrow: GlucoseTrendArrow
    ) : GlucoseDisplayState()

    /** No reading has ever been recorded (fresh install, no permissions yet, etc). */
    object NoData : GlucoseDisplayState()
}
