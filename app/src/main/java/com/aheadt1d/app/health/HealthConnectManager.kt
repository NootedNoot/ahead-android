package com.aheadt1d.app.health

import android.content.Context
import android.util.Log
import com.aheadt1d.app.BuildConfig
import com.aheadt1d.app.state.DebugGlucoseOverride
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

object HealthConnectManager {

    val READ_GLUCOSE_PERMISSION: String = HealthPermission.getReadPermission(BloodGlucoseRecord::class)

    // Spelled out directly rather than via HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND -
    // that constant was added in a later connect-client alpha than the 1.1.0-alpha07 this project
    // pins, so reference it by name instead of risking an unresolved symbol.
    const val READ_BACKGROUND_PERMISSION = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

    // GlucoseCheckWorker needs both: the data-type read permission, plus background
    // read access since WorkManager's execution context doesn't count as foreground
    // even when triggered from a visible Activity (e.g. the "Check now" button).
    val ALL_PERMISSIONS: Set<String> = setOf(READ_GLUCOSE_PERMISSION, READ_BACKGROUND_PERMISSION)

    fun isAvailable(context: Context): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    fun getClientOrNull(context: Context): HealthConnectClient? =
        if (isAvailable(context)) HealthConnectClient.getOrCreate(context) else null

    /**
     * Shared by GlucoseCheckWorker (backend upload) and the chart (on-device
     * display) - both just want "the last N minutes of readings" and neither
     * should have its own copy of the Health Connect query + permission gate.
     * Returns an empty list rather than throwing if Health Connect isn't
     * available or the permission isn't granted yet.
     */
    suspend fun readGlucosePoints(context: Context, windowMinutes: Long): List<GlucosePoint> {
        val now = Instant.now()
        return readGlucosePointsInRange(context, now.minus(Duration.ofMinutes(windowMinutes)), now)
    }

    /**
     * Same as readGlucosePoints, but for an arbitrary historical [start, end]
     * range rather than a trailing window from now - used by the doctor
     * report export, which needs 14-90+ day lookbacks. Health Connect itself
     * has no trouble with an arbitrary historical range; readGlucosePoints'
     * windowMinutes-from-now shape was just this app's own wrapper, not a
     * platform limitation, so this is the one true query and readGlucosePoints
     * above is now a thin convenience wrapper over it.
     */
    suspend fun readGlucosePointsInRange(context: Context, start: Instant, end: Instant): List<GlucosePoint> {
        // Debug-menu injected data takes over the chart's data source entirely
        // when active, so the exact same range-filter/render path the real
        // Health Connect reads use also exercises injected/scenario data.
        // DebugGlucoseOverride.points is always null in release builds - only
        // the debug-only DebugMenuActivity ever calls setPoints().
        if (BuildConfig.DEBUG) {
            DebugGlucoseOverride.points?.let { overridden ->
                return overridden.filter { it.time.isAfter(start) && !it.time.isAfter(end) }.sortedBy { it.time }
            }
        }

        val client = getClientOrNull(context) ?: return emptyList()

        val granted = client.permissionController.getGrantedPermissions()
        Log.d(TAG, "granted perms: $granted, has glucose read: ${READ_GLUCOSE_PERMISSION in granted}")
        if (READ_GLUCOSE_PERMISSION !in granted) return emptyList()

        // readRecords caps a single response at its pageSize (default 1000) and
        // hands back a pageToken for the rest - a 14+ day report range can
        // easily hold more than 1000 5-min readings, so this must follow the
        // token until Health Connect reports there's nothing left, or a long
        // report silently truncates to whatever the first page happened to
        // cover (the "1000 readings across 3.5 days" bug).
        val records = mutableListOf<BloodGlucoseRecord>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = BloodGlucoseRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageToken = pageToken
                )
            )
            records.addAll(response.records)
            pageToken = response.pageToken
        } while (!pageToken.isNullOrEmpty())

        Log.d(TAG, "records returned: ${records.size}")

        val points = records
            .sortedBy { it.time }
            .map { record -> GlucosePoint(time = record.time, sgv = record.level.inMilligramsPerDeciliter.roundToInt()) }

        logSyncGaps(points)
        return points
    }

    /**
     * Visibility-only: warns when two consecutive readings are further apart
     * than a healthy CGM cadence would ever produce. The gap is upstream (the
     * CGM source's sync into Health Connect), not anything Ahead does with the
     * data - this is here purely so a log trail exists to tell an occasional
     * one-off sync hiccup from a recurring source-reliability problem. No UI or
     * behaviour change; the missing point simply isn't in Health Connect to plot.
     */
    private fun logSyncGaps(points: List<GlucosePoint>) {
        for (i in 1 until points.size) {
            val prev = points[i - 1].time
            val curr = points[i].time
            val gapSeconds = Duration.between(prev, curr).seconds
            if (gapSeconds > GAP_WARN_THRESHOLD_MINUTES * 60) {
                Log.w(
                    TAG,
                    "Gap detected: ${GAP_TIME_FORMATTER.format(prev)} -> ${GAP_TIME_FORMATTER.format(curr)} " +
                        "(${gapSeconds / 60} min)"
                )
            }
        }
    }

    /**
     * Rate of change in mg/dL per minute from the two most recent points in
     * [points] (assumed already sorted ascending by time). Returns null when
     * fewer than two points are available — callers should treat null as
     * "insufficient data to compute a trend", not zero/flat.
     *
     * Uses seconds-level precision for the interval so a 4m50s vs 5m10s gap
     * doesn't add unnecessary rounding error.
     */
    fun calculateRatePerMinute(points: List<GlucosePoint>): Double? {
        if (points.size < 2) return null
        val prev   = points[points.size - 2]
        val latest = points[points.size - 1]
        val secondsBetween = Duration.between(prev.time, latest.time).seconds
        if (secondsBetween <= 0) return null
        return (latest.sgv - prev.sgv) / (secondsBetween / 60.0)
    }

    /** mg/dL change from the second-most-recent point to the most recent.
     *  Returns null when fewer than two points are available. */
    fun calculateDelta(points: List<GlucosePoint>): Int? {
        if (points.size < 2) return null
        return points[points.size - 1].sgv - points[points.size - 2].sgv
    }

    private const val TAG = "HealthConnectManager"

    // A CGM syncing every 2-5 min should never leave a >7 min hole; anything
    // larger is a missed reading upstream. 7 (not 5-6) so ordinary 5-min
    // cadence with a little jitter doesn't trip a false warning.
    private const val GAP_WARN_THRESHOLD_MINUTES = 7L
    private val GAP_TIME_FORMATTER =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
}
