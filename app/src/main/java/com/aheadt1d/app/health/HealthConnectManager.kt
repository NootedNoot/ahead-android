package com.aheadt1d.app.health

import android.content.Context
import android.util.Log
import com.aheadt1d.app.BuildConfig
import com.aheadt1d.app.state.DebugGlucoseOverride
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.aheadt1d.ratemath.RateMath
import org.aheadt1d.ratemath.RatePoint
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
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

    // Read-only, display-purpose activity data for the Health tab (2026-08-03)
    // - deliberately a SEPARATE permission set from ALL_PERMISSIONS above, not
    // folded in: the glucose-read gate (readPoints/canReadGlucose) must never
    // start failing just because someone declined an exercise/sleep prompt.
    // Not currently fed into any alert/severity/guess logic - purely
    // informational. Steps are NOT here - see StepTracker, which reads the
    // phone's own step-counter sensor directly instead of going through
    // Health Connect (Samsung Health turned out not to be syncing steps into
    // HC at all, so that read was silently always empty).
    val READ_EXERCISE_PERMISSION: String = HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    val READ_SLEEP_PERMISSION: String = HealthPermission.getReadPermission(SleepSessionRecord::class)
    val ACTIVITY_PERMISSIONS: Set<String> = setOf(READ_EXERCISE_PERMISSION, READ_SLEEP_PERMISSION)

    fun isAvailable(context: Context): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    fun getClientOrNull(context: Context): HealthConnectClient? =
        if (isAvailable(context)) HealthConnectClient.getOrCreate(context) else null

    /** Whether a glucose read can actually succeed right now - not just
     *  whether the SDK is available, but whether this app currently holds
     *  the read-glucose grant. Deliberately narrower than GlucoseCheckRunner's
     *  ALL_PERMISSIONS check (which also requires READ_HEALTH_DATA_IN_BACKGROUND,
     *  only needed by a background execution context): callers running in the
     *  foreground, like MainActivity's own chart read, only need this one. */
    suspend fun canReadGlucose(context: Context): Boolean {
        val client = getClientOrNull(context) ?: return false
        return READ_GLUCOSE_PERMISSION in client.permissionController.getGrantedPermissions()
    }

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
        // 2026-08-03: added while tracking down a duplicate-writer problem
        // (see collapseDuplicateWrites' doc) - Health Connect never exposed
        // WHICH app wrote a given record anywhere else in this codebase, so
        // there was no way to confirm/rule out a second writer besides
        // guessing from record counts. Debug-only: real per-record metadata,
        // not glucose values, but still not something a release build's
        // logcat should carry.
        if (BuildConfig.DEBUG && records.isNotEmpty()) {
            val byWriter = records.groupingBy { it.metadata.dataOrigin.packageName }.eachCount()
            Log.d(TAG, "record writers in this window: $byWriter")
        }

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

    /** Converts this app's own point type down to the shared module's
     *  framework-free one - see ahead-rate-math/CLAUDE.md. */
    private fun GlucosePoint.toRatePoint() = RatePoint(time.toEpochMilli(), sgv)

    /**
     * Rate of change in mg/dL per minute from the two most recent DISTINCT
     * points in [points]. Returns null when fewer than two distinct points
     * are available — callers should treat null as "insufficient data to
     * compute a trend", not zero/flat.
     *
     * Delegates to ahead-rate-math (shared with ahead-lite-android) - see
     * that module's RateMath.kt for the actual dedup/slope logic and the
     * 2026-08-03 incident (two writer apps flooding Health Connect with
     * near-duplicate records) that made the dedup step necessary in the
     * first place.
     */
    fun calculateRatePerMinute(points: List<GlucosePoint>): Double? =
        RateMath.ratePerMinute(points.map { it.toRatePoint() })

    /** mg/dL change from the second-most-recent DISTINCT point to the most
     *  recent. Returns null when fewer than two distinct points are available. */
    fun calculateDelta(points: List<GlucosePoint>): Int? =
        RateMath.delta(points.map { it.toRatePoint() })

    /** Which of [ACTIVITY_PERMISSIONS] are currently granted - the Health tab
     *  shows each stat independently (a user might grant steps but not
     *  sleep), so callers check membership per-permission, not as a group. */
    suspend fun grantedActivityPermissions(context: Context): Set<String> {
        val client = getClientOrNull(context) ?: return emptySet()
        return client.permissionController.getGrantedPermissions().intersect(ACTIVITY_PERMISSIONS)
    }

    /** Every ExerciseSessionRecord starting today, newest first - just enough
     *  fields for a simple list (type/title/start/end), not the full route/
     *  lap detail Health Connect can carry. Null (not empty) when the
     *  permission isn't granted. */
    suspend fun readTodayExerciseSessions(context: Context): List<ExerciseSessionRecord>? {
        val client = getClientOrNull(context) ?: return null
        if (READ_EXERCISE_PERMISSION !in client.permissionController.getGrantedPermissions()) return null

        val zone = ZoneId.systemDefault()
        val startOfDay = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = ExerciseSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startOfDay, Instant.now()),
            )
        )
        return response.records.sortedByDescending { it.startTime }
    }

    /** The most recent SleepSessionRecord that ended within the last 24h -
     *  "last night's sleep" without hardcoding a specific bedtime window,
     *  since that varies person to person and this is display-only context,
     *  not something safety-critical logic depends on. Null (not a missing
     *  session) also means "permission not granted" - same distinction as
     *  the steps/exercise readers above. */
    suspend fun readMostRecentSleepSession(context: Context): SleepSessionRecord? {
        val client = getClientOrNull(context) ?: return null
        if (READ_SLEEP_PERMISSION !in client.permissionController.getGrantedPermissions()) return null

        val now = Instant.now()
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(now.minus(Duration.ofHours(24)), now),
            )
        )
        return response.records.maxByOrNull { it.endTime }
    }

    private const val TAG = "HealthConnectManager"

    // A CGM syncing every 2-5 min should never leave a >7 min hole; anything
    // larger is a missed reading upstream. 7 (not 5-6) so ordinary 5-min
    // cadence with a little jitter doesn't trip a false warning.
    private const val GAP_WARN_THRESHOLD_MINUTES = 7L
    private val GAP_TIME_FORMATTER =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
}
