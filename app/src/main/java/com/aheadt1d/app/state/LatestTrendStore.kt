package com.aheadt1d.app.state

import android.content.Context
import androidx.core.content.edit
import com.aheadt1d.app.setup.SetupPrefs
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

// Source-aware staleness. Every surface (ongoing notification, main screen,
// setup wizard's verify step, debug injection) routes through isStale() below,
// so a reading that's too old to trust is never shown - or alerted on - as if
// it were current.
//
// The threshold has to absorb the WHOLE pipeline, not just the sensor cadence:
//   apparent age of RawReading = CGM reading gap
//     + CGM-app -> Health Connect sync latency (Dexcom batches its HC writes;
//       1-6 min is normal)
//     + up to one full 5-min check cycle before the runner reads it.
// 2026-07-26: previously 12 (Dexcom) / 15 (default), which budgeted only the
// reading gap. Steady-state healthy apparent age already reaches ~10 min, so
// Dexcom had ~2 min of real headroom and ordinary sync jitter produced false
// "No new data" flickers - each of which is also a brief alert-blind window,
// since severity evaluation stops while the state is Stale.
// 2026-07-28: widening to 18 traded too far the other way - a real gap sat
// silent as a normal-looking reading for up to 18 minutes, which is what
// prompted this note (a genuine Dexcom disconnect only got flagged at 16+
// min in, well past what "3 missed readings" should mean). Re-tightened to
// exactly 3 missed 5-min cycles: deliberately accepting back some of the
// false-flicker risk described above in exchange for a disconnect never
// silently sitting past 15 minutes.
//   Dexcom 15  = exactly 3 missed 5-min cycles, no extra pipeline-lag padding.
//   Default 22 = Juggluco's normal 10-15 min gaps (see HealthConnectManager's
//                gap log) + worst-case 5-min read lag + sync latency.
// A true outage is still declared well inside the runner's 45-min read window.
const val STALE_THRESHOLD_DEXCOM_MINUTES = 15L
const val STALE_THRESHOLD_DEFAULT_MINUTES = 22L
// AheadBLE (2026-08-03): our own direct-BLE G7 reader, replacing Juggluco as
// the primary source going forward. Its latency budget has no Dexcom-app
// batching term (ConnectionService writes each reading to Health Connect the
// instant GattModule delivers it) and no Juggluco-style multi-reading gaps -
// just the G7's own ~5-min reading cadence + up to one 5-min runner cycle
// before it's read = 2 missed cycles, tighter than Dexcom's budgeted 3. This
// deliberately trades away some false-flicker margin for faster detection,
// at the owner's explicit request: a silent extended gap here means the
// background connection itself has failed (not yet hardened with boot-start
// or a battery-optimization exemption - see ConnectionService), which is
// exactly the "needs user intervention" case this threshold exists to catch.
const val STALE_THRESHOLD_AHEADBLE_MINUTES = 10L

/** The staleness cutoff for the currently configured CGM source. */
fun staleThresholdMinutes(context: Context): Long =
    when (SetupPrefs.cgmPath(context)) {
        SetupPrefs.PATH_DEXCOM -> STALE_THRESHOLD_DEXCOM_MINUTES
        SetupPrefs.PATH_AHEADBLE -> STALE_THRESHOLD_AHEADBLE_MINUTES
        else -> STALE_THRESHOLD_DEFAULT_MINUTES
    }

/** Whole minutes since [raw] was recorded, or null when there's no reading.
 *  First-class so no layer has to re-derive "time since last reading". */
fun minutesSinceReading(raw: RawReading?): Long? =
    if (raw == null) null else (System.currentTimeMillis() - raw.time) / 60_000

/** Whether a reading recorded at [readingTimeMillis] is too old to treat as
 *  current. THE staleness rule - every surface calls this (directly or via the
 *  RawReading overload below) rather than re-deriving the age/threshold
 *  comparison, so the boundary can never drift between the notification, the
 *  main screen, and the wizard. */
fun isStale(context: Context, readingTimeMillis: Long): Boolean =
    (System.currentTimeMillis() - readingTimeMillis) / 60_000 >= staleThresholdMinutes(context)

/** [isStale] for the persisted latest reading; absent counts as stale. */
fun isStale(context: Context, raw: RawReading?): Boolean =
    raw == null || isStale(context, raw.time)

/**
 * App-side reasons the glucose read pipeline is blocked, diagnosed by
 * GlucoseCheckRunner on every cycle - distinguished from a genuine CGM data
 * gap (reason null) so stale-state copy can point at the actual fix instead
 * of always blaming the sensor.
 */
enum class ReadBlockedReason { PERMISSION_MISSING, HC_UNAVAILABLE }

/** The one place stale-state guidance copy lives - shared by the ongoing
 *  notification (collapsed + expanded), the signal-lost alert, and the main
 *  screen's status line, so no surface can misdirect differently from the
 *  others. Null reason = no app-side blockage diagnosed: the gap is (as far
 *  as the app can tell) upstream, so pointing at the CGM is honest. */
fun staleGuidance(reason: ReadBlockedReason?): String = when (reason) {
    ReadBlockedReason.PERMISSION_MISSING -> "Check Ahead's app permissions — Health Connect access was lost."
    ReadBlockedReason.HC_UNAVAILABLE -> "Health Connect isn't available — open Ahead to reconnect."
    null -> "Ahead is not getting CGM data — check your CGM app or sensor."
}

// How close the backend trend's scored timestamp must be to the raw Health
// Connect reading's time before anything backend-derived (rate, severity,
// projection) is trusted for display against that reading. The backend dedups
// server-side and can lag or stall - a trend scored for a much older reading
// must not decorate a newer one.
const val TREND_MATCH_TOLERANCE_MS = 10 * 60_000L

/**
 * THE rate-of-change (mg/dL/min) to display anywhere in the UI - the single
 * source of truth shared by the main screen and the persistent notification,
 * so one check cycle can never show two different rates on two surfaces.
 *
 * The backend's rate (trend-detector.js) is authoritative when its trend was
 * scored for this same reading (within [TREND_MATCH_TOLERANCE_MS]) - it's the
 * deterministic, safety-critical calculation in the pipeline. The on-device
 * two-point rate is only a fallback for when the backend hasn't scored this
 * reading (offline, dedup'd, slow). Null when no source has a usable rate
 * (e.g. cold start with a single reading).
 */
fun effectiveRatePerMinute(raw: RawReading?, trend: LatestTrend?): Double? {
    if (raw == null) return null
    val trendIsCurrent = trend != null && abs(trend.date - raw.time) <= TREND_MATCH_TOLERANCE_MS
    return if (trendIsCurrent) trend?.rate ?: raw.ratePerMinute else raw.ratePerMinute
}

/** One rule-engine hypothesis for a glucose event. Always a question in the
 *  copy; confidence is "high" | "medium" | "low". */
data class Guess(val label: String, val confidence: String)

data class LatestTrend(
    val currentValue: Int,
    val severity: String,
    val rate: Double?,
    val projected: Int?,
    // 30-min (extended) projection. Shown alongside the 15-min one so the alert
    // text never implies a single window when the tier can be decided off either.
    val projectedExtended: Int?,
    val date: Long,
    // Contextual guesses from the backend guess-engine, only populated during an
    // actual event (empty otherwise). Ranked high -> low confidence.
    val guesses: List<Guess> = emptyList()
)

/**
 * The most recent glucose point read straight from Health Connect, tracked
 * separately from LatestTrend. LatestTrend only advances when the backend's
 * check-trend call returns a newly-scored reading (it dedups server-side and
 * can silently stop advancing - e.g. after a redeploy resets its in-memory
 * dedup state - while Health Connect keeps producing fresh points every
 * run). Anything that needs to know "is the data actually fresh" - like the
 * persistent notification's staleness check - should use this, not
 * LatestTrend.date.
 */
data class RawReading(
    val value: Int,
    val time: Long,
    // Rate of change in mg/dL per minute from the two most recent HC points.
    // Null when only one point is available (cold start / first CGM sync).
    val ratePerMinute: Double?,
    // Change in mg/dL from the second-most-recent HC reading to this one.
    // Null when only one point is available. Derived from the same two points
    // as ratePerMinute, stored here so it survives service restarts without
    // any in-memory state tracking in GlucoseStatusService.
    val deltaFromPrevious: Int?,
    // ADDED 2026-08-20: true when THIS specific reading came from
    // AheadBLE V3's direct broadcast fallback (BroadcastGlucoseBuffer), not
    // a verified Health Connect record - see GlucoseBroadcastReceiver's class
    // doc. Defaults false so every existing call site (and persisted prefs
    // predating this field) is unaffected. Not currently surfaced in the UI -
    // that's a real follow-up, not done here - but the data layer records the
    // distinction rather than silently treating it as equivalent to a
    // verified read, per the owner's explicit direction.
    val wasBroadcastSupplemented: Boolean = false
)

/**
 * SharedPreferences-backed persistence for the latest trend result, so it
 * survives the app process being killed and restarted. LatestTrendRepository
 * is the API everything else should use - this is just its storage layer.
 */
object LatestTrendStore {
    private const val PREFS_NAME = "ahead_latest_trend"
    private const val KEY_CURRENT_VALUE = "current_value"
    private const val KEY_SEVERITY = "severity"
    private const val KEY_RATE = "rate"
    private const val KEY_PROJECTED = "projected"
    private const val KEY_PROJECTED_EXTENDED = "projected_extended"
    private const val KEY_DATE = "date"
    private const val KEY_GUESSES = "guesses"
    private const val NO_PROJECTED = Int.MIN_VALUE

    fun save(context: Context, trend: LatestTrend) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putInt(KEY_CURRENT_VALUE, trend.currentValue)
            putString(KEY_SEVERITY, trend.severity)
            putFloat(KEY_RATE, trend.rate?.toFloat() ?: Float.NaN)
            putInt(KEY_PROJECTED, trend.projected ?: NO_PROJECTED)
            putInt(KEY_PROJECTED_EXTENDED, trend.projectedExtended ?: NO_PROJECTED)
            putLong(KEY_DATE, trend.date)
            putString(KEY_GUESSES, guessesToJson(trend.guesses))
        }
    }

    fun load(context: Context): LatestTrend? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_CURRENT_VALUE)) return null

        val rate = prefs.getFloat(KEY_RATE, Float.NaN)
        val projected = prefs.getInt(KEY_PROJECTED, NO_PROJECTED)
        val projectedExtended = prefs.getInt(KEY_PROJECTED_EXTENDED, NO_PROJECTED)

        return LatestTrend(
            currentValue = prefs.getInt(KEY_CURRENT_VALUE, 0),
            severity = prefs.getString(KEY_SEVERITY, "none") ?: "none",
            rate = if (rate.isNaN()) null else rate.toDouble(),
            projected = if (projected == NO_PROJECTED) null else projected,
            projectedExtended = if (projectedExtended == NO_PROJECTED) null else projectedExtended,
            date = prefs.getLong(KEY_DATE, 0L),
            guesses = guessesFromJson(prefs.getString(KEY_GUESSES, null))
        )
    }

    private fun guessesToJson(guesses: List<Guess>): String = JSONArray().apply {
        guesses.forEach { guess ->
            put(JSONObject().apply {
                put("label", guess.label)
                put("confidence", guess.confidence)
            })
        }
    }.toString()

    // Backend responses are network input. A malformed cached guess must never
    // prevent the last known glucose value from loading after a process restart.
    private fun guessesFromJson(json: String?): List<Guess> = runCatching {
        if (json.isNullOrBlank()) return emptyList()
        val array = JSONArray(json)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val label = item.optString("label").trim()
                if (label.isNotEmpty()) add(Guess(label, item.optString("confidence", "low")))
            }
        }
    }.getOrDefault(emptyList())

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { clear() }
    }
}

/** Same persistence pattern as LatestTrendStore, kept in its own small prefs
 *  file since it's updated far more often (every Worker run) and has nothing
 *  to do with the backend-derived trend. */
object RawReadingStore {
    private const val PREFS_NAME = "ahead_latest_raw_reading"
    private const val KEY_VALUE = "value"
    private const val KEY_TIME = "time"
    private const val KEY_RATE = "rate_per_minute"
    private const val KEY_DELTA = "delta_from_previous"
    private const val KEY_BROADCAST_SUPPLEMENTED = "was_broadcast_supplemented"
    private const val NO_DELTA = Int.MIN_VALUE

    fun save(context: Context, reading: RawReading) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putInt(KEY_VALUE, reading.value)
            putLong(KEY_TIME, reading.time)
            putFloat(KEY_RATE, reading.ratePerMinute?.toFloat() ?: Float.NaN)
            putInt(KEY_DELTA, reading.deltaFromPrevious ?: NO_DELTA)
            putBoolean(KEY_BROADCAST_SUPPLEMENTED, reading.wasBroadcastSupplemented)
        }
    }

    fun load(context: Context): RawReading? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_TIME)) return null
        val rate = prefs.getFloat(KEY_RATE, Float.NaN)
        val delta = prefs.getInt(KEY_DELTA, NO_DELTA)
        return RawReading(
            value = prefs.getInt(KEY_VALUE, 0),
            time = prefs.getLong(KEY_TIME, 0L),
            ratePerMinute = if (rate.isNaN()) null else rate.toDouble(),
            deltaFromPrevious = if (delta == NO_DELTA) null else delta,
            // getBoolean's default (false) is also the right answer for prefs
            // written before this key existed - not just a placeholder.
            wasBroadcastSupplemented = prefs.getBoolean(KEY_BROADCAST_SUPPLEMENTED, false)
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { clear() }
    }
}
