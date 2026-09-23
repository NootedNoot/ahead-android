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
    return raw.ratePerMinute ?: if (trendIsCurrent) trend?.rate else null
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
    val wasBroadcastSupplemented: Boolean = false,
    // ADDED 2026-08-29: whether any reading in the trailing 40 minutes was
    // <= 80 mg/dL - the exact trigger SeverityEngine's post-hypo recovery
    // grace period needs (see its RECOVERING_FROM_LOW_TRIGGER_MGDL/
    // POST_HYPO_RECOVERY_GRACE_WINDOW_MS). Computed once in
    // GlucoseCheckRunner (where the reading-history window is already being
    // read for the backend POST) rather than re-derived at every call site.
    // Found during a fragmentation audit that GlucoseDisplayState never
    // actually passed this to SeverityEngine.classify() at all - the whole
    // grace-period feature was built and tested but silently inert on the
    // real device. Defaults false so pre-existing persisted prefs (written
    // before this field existed) degrade to "not recovering," the same
    // behavior as before this fix - never a false positive from a missing key.
    val recoveringFromLow: Boolean = false,
    // ADDED 2026-08-29 (the "smarter math" wiring pass):
    //
    // recentRates - up to the last 3 point-to-point rates (oldest -> newest),
    // exactly what SeverityEngine.assessRateTrajectory needs to classify
    // DECELERATING/NOISY. Found DURING this pass that GlucoseDisplayState's
    // real call to SeverityEngine.classify() never passed this at all - it
    // silently defaulted to emptyList(), which means trajectory
    // classification (and therefore decay-based projection AND noisy-spike
    // RED suppression) has NEVER actually engaged on a real device, despite
    // being fully built and tested. This is the single most consequential
    // gap found this session - fixing it is also a prerequisite for
    // TreatmentEffectWindow's physiological decay to ever have anything to
    // apply to (it only matters inside the DECELERATING branch).
    //
    // severityRatePerMinute - RateConsensus's median of three independent
    // rate estimates (2-point slope, Kalman filter, linear regression),
    // used ONLY to feed SeverityEngine's severity/projection decision - the
    // DISPLAYED rate/arrow (this class's own [ratePerMinute] field) stays
    // exactly the raw 2-point number it's always been, so what someone sees
    // on screen never changes, only what quietly drives the alert decision
    // underneath it gets more robust to any single method's blind spot.
    //
    // excursionDurationMinutes - how long the current low/high excursion has
    // actually been running, feeding TreatmentEffectWindow's asymmetric
    // 30-min-low/90-min-high treatment-effect trust window.
    //
    // All three default to empty/null so pre-existing persisted prefs
    // degrade to the exact same behavior as before this pass (empty
    // recentRates -> CONSISTENT trajectory, same as always; null rate/
    // duration -> classify() falls back to the plain rate/undamped decay it
    // already used) - never a behavior change from a missing key, only from
    // a genuinely fresh read going through GlucoseCheckRunner's new
    // computation.
    val recentRates: List<Double> = emptyList(),
    val severityRatePerMinute: Double? = null,
    val excursionDurationMinutes: Long? = null,
    // ADDED 2026-08-30: whether RateConsensus's three independent rate
    // estimates agreed with each other (see SeverityEngine.classify's
    // ratesAgree param). Defaults true for the same reason severity RatePer
    // Minute-adjacent fields do - a pre-existing persisted RawReading
    // written before this field existed should degrade to "don't suppress
    // RED on this basis," not silently gain a new suppression path it was
    // never evaluated against.
    val rateMethodsAgree: Boolean = true,
    // ADDED 2026-09-23 (Ticket 017): which TreatmentEffectWindow.CauseTier (ahead-rate-math)
    // explains the current low/high excursion, if anything does - TREATED (a matching-direction
    // correction is logged and still plausibly working), EXERCISE_ACTIVE/EXERCISE_RISK (a
    // recently-logged exercise event), or UNEXPLAINED (neither). Feeds SeverityEngine.classify's
    // causeTier param AND AlertCoordinator's low-side stability buffer (see its
    // stabilityReadingsRequired) - an UNEXPLAINED or EXERCISE_RISK reversal needs more
    // confirmation before an episode is declared resolved than a TREATED/EXERCISE_ACTIVE one,
    // because there's no known reason yet to trust an early reversal.
    //
    // Deliberately NOT a parameter of fromPoints() below, unlike every other field in this
    // class: fromPoints is a pure function with no Context, and computing a real CauseTier needs
    // Context (to read PlateauCoordinator's correction-window prefs and query the Room event DB
    // for the most recent logged exercise). It's set via a subsequent .copy() in
    // GlucoseCheckRunner, the one place in the live pipeline that has both a fresh RawReading and
    // a Context at the same time - see its own comment at the call site.
    //
    // Defaults null (not "UNEXPLAINED") so every pre-existing construction of RawReading
    // elsewhere in the codebase (debug scenarios, tests, prefs written before this field
    // existed) keeps compiling and flows through to SeverityEngine.classify/
    // stabilityReadingsRequired as "no cause info" - which both treat identically to UNEXPLAINED
    // (fail toward requiring more confirmation, never less) per the ratemath contract, so a
    // missing value is exactly as safe as an explicit UNEXPLAINED, never more permissive.
    //
    // CORRECTED 2026-09-23, same day - this was briefly FALSE for one of the two consumers
    // (TreatmentEffectWindow.projectWithPhysiologicalDecay originally let null fall back to the
    // pre-cause-tier fixed windows, which on the low side is MORE permissive than UNEXPLAINED, not
    // equivalent - see that function's own doc). Adversarial review also found this null default
    // is reachable in real, non-debug usage, not just a theoretical fallback: MainActivity's own,
    // more frequent chart-refresh path (syncRawReadingToRepository) independently calls
    // RawReading.fromPoints and writes a fresh RawReading with no causeTier at all, so it can win
    // the race against GlucoseCheckRunner's slower cadence and briefly overwrite an already-tiered
    // reading whenever the app is open - plausibly exactly when someone is anxiously watching a
    // real low. RawReadingStore.save/load (below) now persists this field for the same reason (a
    // process restart used to silently drop it too) - defense in depth now that null is safe by
    // construction, not the only thing preventing a wrong answer. The MainActivity race itself is
    // NOT separately fixed (only made safe, never dangerous) - a known, accepted, documented gap.
    val causeTier: org.aheadt1d.ratemath.CauseTier? = null
) {
    companion object {
        fun fromPoints(
            points: List<com.aheadt1d.app.health.GlucosePoint>,
            wasBroadcastSupplemented: Boolean = false
        ): RawReading? {
            val latest = points.lastOrNull() ?: return null
            val recoveringFromLow = points.any { p ->
                p.sgv <= org.aheadt1d.ratemath.SeverityEngine.RECOVERING_FROM_LOW_TRIGGER_MGDL &&
                    java.time.Duration.between(p.time, latest.time).toMillis() <= org.aheadt1d.ratemath.SeverityEngine.POST_HYPO_RECOVERY_GRACE_WINDOW_MS
            }
            val ratePoints = points.map { org.aheadt1d.ratemath.RatePoint(it.time.toEpochMilli(), it.sgv) }
            val recentRates = org.aheadt1d.ratemath.RateMath.recentRates(ratePoints, count = 3)
            val rateVote = org.aheadt1d.ratemath.RateConsensus.vote(ratePoints)
            val severityRatePerMinute = org.aheadt1d.ratemath.RateConsensus.consensusRate(rateVote)
            val rateMethodsAgree = org.aheadt1d.ratemath.RateConsensus.estimatesAgree(rateVote)
            val isLowSide = latest.sgv < 125
            val excursionDurationMinutes = org.aheadt1d.ratemath.TreatmentEffectWindow
                .excursionDurationMinutes(ratePoints, isLow = isLowSide)

            return RawReading(
                value = latest.sgv,
                time = latest.time.toEpochMilli(),
                ratePerMinute = com.aheadt1d.app.health.HealthConnectManager.calculateRatePerMinute(points),
                deltaFromPrevious = com.aheadt1d.app.health.HealthConnectManager.calculateDelta(points),
                wasBroadcastSupplemented = wasBroadcastSupplemented,
                recoveringFromLow = recoveringFromLow,
                recentRates = recentRates,
                severityRatePerMinute = severityRatePerMinute,
                excursionDurationMinutes = excursionDurationMinutes,
                rateMethodsAgree = rateMethodsAgree
            )
        }
    }
}

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
    private const val KEY_RECOVERING_FROM_LOW = "recovering_from_low"
    private const val KEY_RECENT_RATES = "recent_rates"
    private const val KEY_SEVERITY_RATE = "severity_rate_per_minute"
    private const val KEY_EXCURSION_DURATION = "excursion_duration_minutes"
    private const val KEY_RATE_METHODS_AGREE = "rate_methods_agree"
    private const val KEY_CAUSE_TIER = "cause_tier"
    private const val NO_DELTA = Int.MIN_VALUE
    private const val NO_EXCURSION_DURATION = -1L

    fun save(context: Context, reading: RawReading) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putInt(KEY_VALUE, reading.value)
            putLong(KEY_TIME, reading.time)
            putFloat(KEY_RATE, reading.ratePerMinute?.toFloat() ?: Float.NaN)
            putInt(KEY_DELTA, reading.deltaFromPrevious ?: NO_DELTA)
            putBoolean(KEY_BROADCAST_SUPPLEMENTED, reading.wasBroadcastSupplemented)
            putBoolean(KEY_RECOVERING_FROM_LOW, reading.recoveringFromLow)
            // Comma-joined - SharedPreferences has no native list-of-double
            // type, and this list is always small (at most 3 entries).
            putString(KEY_RECENT_RATES, reading.recentRates.joinToString(","))
            putFloat(KEY_SEVERITY_RATE, reading.severityRatePerMinute?.toFloat() ?: Float.NaN)
            // Excursion duration is a real, meaningful 0 (currently in-band,
            // just not IN an excursion) - can't reuse Int.MIN_VALUE-style
            // sentinel semantics the same way delta does, since 0 here is a
            // valid answer, not "absent." -1 is never a real duration.
            putLong(KEY_EXCURSION_DURATION, reading.excursionDurationMinutes ?: NO_EXCURSION_DURATION)
            putBoolean(KEY_RATE_METHODS_AGREE, reading.rateMethodsAgree)
            // 2026-09-23 (Ticket 017, added during adversarial review - this key was missing from the
            // original pass): without this, a process restart (a real, recurring failure mode for this
            // app - aggressive OEM foreground-service kills, mitigated elsewhere by the WorkManager
            // watchdog) reloaded the last RawReading with causeTier forced back to null, right before
            // the render loop's first collectLatest fires off the just-reloaded StateFlow value - a
            // real window where a genuinely TREATED or UNEXPLAINED classification silently reverted
            // to "unknown" for one render pass. causeTier=null is now SAFE by construction (see
            // TreatmentEffectWindow.projectWithPhysiologicalDecay's own doc - it resolves exactly like
            // UNEXPLAINED), so this is defense in depth, not the only thing standing between a process
            // restart and a wrong answer - but a TREATED/EXERCISE_ACTIVE reading correctly round-
            // tripping through a restart, instead of briefly downgrading to the more cautious tier
            // until the next check cycle, is still worth the one extra key. name stores CauseTier.name
            // (enum ordinal is NOT used - see load()'s own comment for why that matters across a
            // future reordering of the enum).
            if (reading.causeTier != null) putString(KEY_CAUSE_TIER, reading.causeTier.name) else remove(KEY_CAUSE_TIER)
        }
    }

    fun load(context: Context): RawReading? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_TIME)) return null
        val rate = prefs.getFloat(KEY_RATE, Float.NaN)
        val delta = prefs.getInt(KEY_DELTA, NO_DELTA)
        val severityRate = prefs.getFloat(KEY_SEVERITY_RATE, Float.NaN)
        val excursionDuration = prefs.getLong(KEY_EXCURSION_DURATION, NO_EXCURSION_DURATION)
        val recentRates = prefs.getString(KEY_RECENT_RATES, null)
            ?.takeIf { it.isNotBlank() }
            ?.split(",")
            ?.mapNotNull { it.toDoubleOrNull() }
            ?: emptyList()
        return RawReading(
            value = prefs.getInt(KEY_VALUE, 0),
            time = prefs.getLong(KEY_TIME, 0L),
            ratePerMinute = if (rate.isNaN()) null else rate.toDouble(),
            deltaFromPrevious = if (delta == NO_DELTA) null else delta,
            // getBoolean's default (false) is also the right answer for prefs
            // written before this key existed - not just a placeholder.
            wasBroadcastSupplemented = prefs.getBoolean(KEY_BROADCAST_SUPPLEMENTED, false),
            recoveringFromLow = prefs.getBoolean(KEY_RECOVERING_FROM_LOW, false),
            recentRates = recentRates,
            severityRatePerMinute = if (severityRate.isNaN()) null else severityRate.toDouble(),
            excursionDurationMinutes = if (excursionDuration == NO_EXCURSION_DURATION) null else excursionDuration,
            // Default true (permissive) for prefs written before this key
            // existed - matches RawReading.rateMethodsAgree's own default.
            rateMethodsAgree = prefs.getBoolean(KEY_RATE_METHODS_AGREE, true),
            // Stored by NAME, not ordinal - an ordinal would silently point at the wrong tier (or
            // throw) the moment CauseTier's declaration order ever changes; a name that no longer
            // matches any entry (a future rename, or prefs written by an older version before a tier
            // was added/removed) falls back to null exactly like a prefs file written before this key
            // existed at all - both degrade to the same UNEXPLAINED-equivalent, never-more-permissive
            // safe default (see TreatmentEffectWindow.projectWithPhysiologicalDecay's own doc).
            causeTier = prefs.getString(KEY_CAUSE_TIER, null)?.let { name ->
                runCatching { org.aheadt1d.ratemath.CauseTier.valueOf(name) }.getOrNull()
            }
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { clear() }
    }
}
