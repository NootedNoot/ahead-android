package com.aheadt1d.app.chart

import com.aheadt1d.app.health.GlucosePoint
import java.time.Duration

/**
 * Splits readings into contiguous runs, breaking wherever two consecutive
 * readings are further apart than [maxGap] - a long report/graph can have
 * real multi-hour/day holes (sensor off, nothing synced), and a straight
 * line across those would misleadingly imply readings that don't exist.
 * Shared by the report Canvas renderer (draws each segment independently,
 * no connecting line between them), the live chart (one LineDataSet per
 * segment), and the interactive export (one polyline per segment in the
 * embedded JSON).
 */
object GapSegmenter {
    fun segment(readings: List<GlucosePoint>, maxGap: Duration = Duration.ofMinutes(20)): List<List<GlucosePoint>> {
        if (readings.isEmpty()) return emptyList()
        val sorted = readings.sortedBy { it.time }
        val segments = mutableListOf(mutableListOf(sorted.first()))
        for (i in 1 until sorted.size) {
            val prev = sorted[i - 1]
            val point = sorted[i]
            if (Duration.between(prev.time, point.time) > maxGap) {
                segments.add(mutableListOf(point))
            } else {
                segments.last().add(point)
            }
        }
        return segments
    }
}
