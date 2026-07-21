package com.aheadt1d.app.chart

import com.aheadt1d.app.events.UserEvent
import java.time.Instant

/** Chronological, 1-indexed numbering shared between every place event
 *  markers get drawn/listed (chart overlay, PDF legend, HTML export) -
 *  callers must use [EventMarkerLayout.buildEventMarkers] (not their own
 *  sort) so the numbers always agree everywhere. */
data class EventMarker(val number: Int, val event: UserEvent)

data class PositionedMarker(val marker: EventMarker, val x: Float, val row: Int)

/**
 * Event-marker collision layout, extracted verbatim from
 * GlucoseReportChartRenderer's original drawEventMarkers - markers are
 * chronological (buildEventMarkers sorts by timestamp), so x is
 * non-decreasing as we iterate. For each marker, greedily pick the first row
 * whose last-placed marker is at least minSpacingPx away (or an empty row) -
 * deliberately NOT a simple alternating toggle between two rows: with 3+
 * events clustered together, an alternating toggle only checks the
 * immediately previous marker, so it can still collide two positions later
 * (e.g. marker 2 and marker 4 both landing on the "odd" row). Tracking each
 * row's own last x independently avoids that regardless of cluster size.
 */
object EventMarkerLayout {
    fun buildEventMarkers(events: List<UserEvent>): List<EventMarker> =
        events.sortedBy { it.timestamp }.mapIndexed { index, event -> EventMarker(index + 1, event) }

    fun layoutRows(
        markers: List<EventMarker>,
        xForTime: (Instant) -> Float,
        minSpacingPx: Float,
        maxRows: Int,
    ): List<PositionedMarker> {
        val lastXPerRow = FloatArray(maxRows) { Float.NEGATIVE_INFINITY }
        return markers.map { marker ->
            val x = xForTime(Instant.ofEpochMilli(marker.event.timestamp))
            var row = 0
            while (row < maxRows - 1 && x - lastXPerRow[row] < minSpacingPx) row++
            lastXPerRow[row] = x
            PositionedMarker(marker, x, row)
        }
    }
}
