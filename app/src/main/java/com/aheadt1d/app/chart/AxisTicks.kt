package com.aheadt1d.app.chart

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

object AxisTicks {
    data class Tick(val instant: Instant, val label: String)
    data class YLabel(val value: Int, val show: Boolean)

    private val DAY_FORMATTER = DateTimeFormatter.ofPattern("MMM d")
    private val HOUR_FORMATTER = DateTimeFormatter.ofPattern("MMM d, h a")

    /**
     * Short ranges (<=2 days) tick at even FRACTIONS of the range, labeled
     * with hour-of-day - there's no "day boundary" to snap to that would read
     * as more natural there.
     *
     * Longer ranges snap ticks to clean local-midnight day boundaries at a
     * fixed interval (1/2/5/14 days depending on total span) instead of
     * dividing the range into a fixed count of equal FRACTIONS - the latter
     * produces irregular gaps like "Jul 1, 4, 6, 8, 11, 13, 15" (a 14-day
     * range split into 6 equal fractions lands on inconsistent day boundaries
     * once rounded for display). A fixed day interval always reads as an
     * intentional cadence instead of an arbitrary fraction.
     */
    fun xAxisTicks(start: Instant, end: Instant, zone: ZoneId = ZoneId.systemDefault()): List<Tick> {
        val totalDays = Duration.between(start, end).toHours() / 24.0

        val instants: List<Instant> = if (totalDays <= 2) {
            val tickCount = 6
            (0..tickCount).map { i ->
                val fraction = i.toDouble() / tickCount
                start.plusMillis((Duration.between(start, end).toMillis() * fraction).toLong())
            }
        } else {
            val intervalDays = when {
                totalDays <= 7 -> 1L
                totalDays <= 21 -> 2L
                totalDays <= 60 -> 5L
                else -> 14L
            }
            val startDate = start.atZone(zone).toLocalDate()
            val endDate = end.atZone(zone).toLocalDate()
            val regularTicks = mutableListOf<Instant>()
            var day = startDate
            while (!day.isAfter(endDate)) {
                val instant = day.atStartOfDay(zone).toInstant()
                regularTicks.add(if (instant < start) start else instant)
                day = day.plusDays(intervalDays)
            }
            // Always label the range's actual end - replace the last regular
            // tick if it landed too close (would overlap its label), append
            // otherwise, so the chart never looks like it stops short.
            val lastRegular = regularTicks.lastOrNull()
            if (lastRegular == null || Duration.between(lastRegular, end).toHours() > intervalDays * 24 / 2) {
                regularTicks.add(end)
            } else {
                regularTicks[regularTicks.lastIndex] = end
            }
            regularTicks
        }

        val formatter = if (totalDays <= 2) HOUR_FORMATTER else DAY_FORMATTER
        return instants.map { instant -> Tick(instant, formatter.withZone(zone).format(instant)) }
    }

    /**
     * [values] are always present (e.g. as gridlines); a label is only
     * included in the "show" set when it lands at least [minSpacingPx] from
     * an already-placed label - at typical chart heights, 40 and 70 (only 30
     * mg/dL apart) compress to under 10px and would render as one smeared
     * number. [priority] determines which labels win a collision (place
     * highest-priority first, e.g. the target band edges 70/180) - later,
     * lower-priority values only lose their label if they'd collide with a
     * higher-priority one already placed.
     */
    fun yAxisLabels(
        values: List<Int>,
        priority: List<Int>,
        yForValue: (Float) -> Float,
        minSpacingPx: Float,
    ): List<YLabel> {
        val placedYs = mutableListOf<Float>()
        val shown = mutableSetOf<Int>()
        priority.forEach { value ->
            val y = yForValue(value.toFloat())
            val collides = placedYs.any { abs(it - y) < minSpacingPx }
            if (!collides) {
                shown.add(value)
                placedYs.add(y)
            }
        }
        return values.map { YLabel(it, it in shown) }
    }
}
