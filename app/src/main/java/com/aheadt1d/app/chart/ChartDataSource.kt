package com.aheadt1d.app.chart

import android.content.Context
import com.aheadt1d.app.data.GlucoseVaultDatabase
import com.aheadt1d.app.events.UserEvent
import com.aheadt1d.app.events.UserEventRepository
import com.aheadt1d.app.health.GlucosePoint
import com.aheadt1d.app.health.HealthConnectManager
import kotlinx.coroutines.flow.first
import org.aheadt1d.ratemath.RateMath
import org.aheadt1d.ratemath.RatePoint
import java.time.Instant

data class ChartData(val range: ChartRange, val readings: List<GlucosePoint>, val events: List<UserEvent>)

/**
 * Single arbitrary-range data-fetching path shared by the live in-app chart,
 * the doctor report, and the interactive export.
 *
 * Dual-Source Resilience:
 * Queries both Google Health Connect AND Ahead's indestructible local SQLite
 * Vault (ahead_vault.db), merging and deduplicating readings. If Health Connect
 * sync lags behind Dexcom or loses historical points, the local SQLite vault
 * fills in all missing gaps seamlessly.
 */
object ChartDataSource {
    suspend fun load(context: Context, range: ChartRange): ChartData {
        // 1. Fetch from Health Connect
        val hcReadings = try {
            HealthConnectManager.readGlucosePointsInRange(context, range.start, range.end)
        } catch (e: Throwable) {
            emptyList()
        }

        // 2. Fetch from local SQLite Vault
        val vault = GlucoseVaultDatabase.getInstance(context)
        val startMs = range.start.toEpochMilli()
        val endMs = range.end.toEpochMilli()

        // Back-populate vault with any fresh Health Connect readings
        for (pt in hcReadings) {
            vault.recordReading(
                epochMillis = pt.time.toEpochMilli(),
                sgv = pt.sgv,
                source = "HEALTH_CONNECT"
            )
        }

        val vaultRecords = vault.getReadingsBetween(startMs, endMs)
        val vaultPoints = vaultRecords.map {
            GlucosePoint(time = Instant.ofEpochMilli(it.epochMillis), sgv = it.sgv)
        }

        // 3. Combine and Deduplicate (via RateMath.collapseDuplicates across 90-sec window)
        val allPoints = (hcReadings + vaultPoints).distinctBy { it.time.toEpochMilli() }.sortedBy { it.time }
        val ratePoints = allPoints.map { RatePoint(it.time.toEpochMilli(), it.sgv) }
        val dedupedRatePoints = RateMath.collapseDuplicates(ratePoints)
        val combinedReadings = dedupedRatePoints.map {
            GlucosePoint(time = Instant.ofEpochMilli(it.epochMillis), sgv = it.sgv)
        }

        // 4. Fetch User Events
        val events = UserEventRepository.eventsInRange(
            context,
            startMs,
            endMs
        ).first()

        return ChartData(range, combinedReadings, events)
    }
}
