package com.aheadt1d.app.chart

import android.content.Context
import com.aheadt1d.app.events.UserEvent
import com.aheadt1d.app.events.UserEventRepository
import com.aheadt1d.app.health.GlucosePoint
import com.aheadt1d.app.health.HealthConnectManager
import kotlinx.coroutines.flow.first

data class ChartData(val range: ChartRange, val readings: List<GlucosePoint>, val events: List<UserEvent>)

/**
 * Single arbitrary-range data-fetching path shared by the live in-app chart,
 * the doctor report, and the interactive export - replaces each screen
 * independently calling HealthConnectManager/UserEventRepository with its own
 * fetch shape (e.g. GraphActivity's old fixed-6h-then-filter approach).
 */
object ChartDataSource {
    suspend fun load(context: Context, range: ChartRange): ChartData {
        val readings = HealthConnectManager.readGlucosePointsInRange(context, range.start, range.end)
        val events = UserEventRepository.eventsInRange(
            context,
            range.start.toEpochMilli(),
            range.end.toEpochMilli()
        ).first()
        return ChartData(range, readings, events)
    }
}
