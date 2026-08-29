package com.aheadt1d.app.work

import android.content.Context
import android.os.RemoteException
import android.util.Log
import com.aheadt1d.app.BuildConfig
import com.aheadt1d.app.alerts.PlateauCoordinator
import com.aheadt1d.app.bridge.BroadcastGlucoseBuffer
import com.aheadt1d.app.events.UserEventRepository
import com.aheadt1d.app.health.GlucosePoint
import com.aheadt1d.app.health.HealthConnectManager
import com.aheadt1d.app.network.BackendClient
import com.aheadt1d.app.notifications.GlucoseStatusService
import com.aheadt1d.app.state.Guess
import com.aheadt1d.app.state.LatestTrend
import com.aheadt1d.app.state.LatestTrendRepository
import com.aheadt1d.app.state.RawReading
import com.aheadt1d.app.state.ReadBlockedReason
import com.aheadt1d.app.tuning.PlateauTuningPrefs
import com.aheadt1d.app.tuning.TuningPrefs
import com.aheadt1d.app.upload.UploadCoordinator
import java.io.IOException
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/**
 * The single glucose-check pipeline, shared by everything that drives a check:
 *  - GlucoseStatusService's 5-minute foreground loop (the PRIMARY cadence).
 *  - GlucoseCheckWorker, which is now both the manual "Check now" one-time job
 *    AND the periodic WorkManager watchdog that resurrects the foreground
 *    service if an aggressive OEM kills it.
 *
 * Extracted verbatim from GlucoseCheckWorker so every caller runs the identical,
 * already-proven chain: read Health Connect -> record the raw reading -> keep the
 * foreground service alive -> run the plateau/correction detector -> POST to the
 * backend for severity/projection and publish the trend.
 *
 * Everything that reacts to a check (the persistent notification, AlertCoordinator,
 * PlateauCoordinator's own state) either observes LatestTrendRepository or is
 * invoked here, so no caller needs extra wiring - it just calls run().
 *
 * All coordinators this touches (AlertCoordinator via the service's render loop,
 * PlateauCoordinator here) are @Synchronized with built-in dedup, so it is safe
 * for the service loop and the Worker watchdog to both run this concurrently -
 * overlapping cycles re-evaluate the same state without double-firing alerts.
 */
object GlucoseCheckRunner {
    private const val TAG = "GlucoseCheckRunner"

    // The backend's trend detector expects the full recent readings array, so we
    // re-read a trailing window each cycle rather than "just what's new" - a wider
    // window costs nothing and covers CGM syncs that land between runs.
    private const val WINDOW_MINUTES = 45L

    /** Mirrors WorkManager's three outcomes so GlucoseCheckWorker can map straight
     *  onto Result; the foreground-service loop just ignores it and runs again on
     *  its next tick. */
    enum class Outcome { SUCCESS, RETRY, FAILURE }

    suspend fun run(context: Context): Outcome = try {
        runInternal(context)
    } finally {
        // Unconditional, regardless of which branch/return below was hit: the
        // persistent notification (and AlertCoordinator) used to only update
        // via GlucoseStatusService's own render() loop, which only runs while
        // that foreground service instance is alive. GlucoseCheckWorker's
        // watchdog run reaches this function too, but its own attempt to
        // resurrect the service (ensureRunning(), called below) can silently
        // fail from a background WorkManager context on API 31+ - so a
        // Worker-only cycle could update this repository with nobody actually
        // posting the refreshed notification. This finally block guarantees a
        // notification refresh every time this function returns, independent
        // of whether the foreground service is actually running - matching
        // the reported bug (notification only updates when the app is opened,
        // not on the CGM's ~5-min cadence).
        GlucoseStatusService.refreshNotification(context)
    }

    private suspend fun runInternal(context: Context): Outcome {
        var points = readPoints(context) ?: return Outcome.FAILURE

        // ADDED 2026-08-20: AheadBLE V3's direct-broadcast redundancy path
        // (see GlucoseBroadcastReceiver's class doc for the full reasoning -
        // this is the ONE place its buffered reading can enter the real
        // pipeline, and only when Health Connect's own read is still missing
        // it). consumeIfNewerThan both checks AND clears the buffer, so a
        // reading Health Connect already covered here is silently discarded,
        // never reinjected, and never treated as anything other than a
        // normal Health Connect point.
        val latestHcTimeMillis = points.lastOrNull()?.time?.toEpochMilli()
        val fallback = BroadcastGlucoseBuffer.consumeIfNewerThan(latestHcTimeMillis)
        var usedBroadcastFallback = false
        if (fallback != null) {
            Log.w(
                TAG,
                "Health Connect read is missing a reading AheadBLE V3 already delivered directly " +
                    "(mgDl=${fallback.mgDl}, ${System.currentTimeMillis() - fallback.timestampMillis}ms old) " +
                    "- using it as an unconfirmed fallback point"
            )
            points = (points + GlucosePoint(time = Instant.ofEpochMilli(fallback.timestampMillis), sgv = fallback.mgDl))
                .sortedBy { it.time }
            usedBroadcastFallback = true
        }

        LatestTrendRepository.updateReadBlocked(null)

        // Signal a successful local read regardless of what happens with the
        // backend below - MainActivity's chart reads Health Connect directly,
        // so it shouldn't have to wait on a trend result to know it's stale.
        LatestTrendRepository.markChecked()

        // Recorded from the raw Health Connect read, not the backend response -
        // the backend dedups server-side and can go a long time without
        // returning anything "new" (e.g. after a redeploy resets its in-memory
        // state), which would otherwise freeze the persistent notification's
        // staleness check on a much older LatestTrend.date even though fresh
        // readings keep arriving.
        points.lastOrNull()?.let { latest ->
            // Any point in the trailing POST_HYPO_RECOVERY_GRACE_WINDOW_MS (40
            // min) at or below RECOVERING_FROM_LOW_TRIGGER_MGDL (80) - the
            // SAME window/threshold SeverityEngine's grace period and
            // trend-detector.js's processNewReading both use. WINDOW_MINUTES
            // above (45) already covers this, so no extra Health Connect read
            // needed. Found during a fragmentation audit that this was never
            // actually computed anywhere on the on-device path - the grace
            // period existed and was tested but silently inert.
            val recoveringFromLow = points.any { p ->
                p.sgv <= org.aheadt1d.ratemath.SeverityEngine.RECOVERING_FROM_LOW_TRIGGER_MGDL &&
                    java.time.Duration.between(p.time, latest.time).toMillis() <= org.aheadt1d.ratemath.SeverityEngine.POST_HYPO_RECOVERY_GRACE_WINDOW_MS
            }

            // The "smarter math" wiring pass (2026-08-29) - same points
            // window, converted once to ahead-rate-math's shared RatePoint
            // shape and reused for all three new computations below.
            val ratePoints = points.map { org.aheadt1d.ratemath.RatePoint(it.time.toEpochMilli(), it.sgv) }

            // Up to the last 3 point-to-point rates - what
            // SeverityEngine.assessRateTrajectory needs to classify
            // DECELERATING/NOISY at all. See RawReading.recentRates' own
            // doc for why this was the single most consequential gap found
            // this session - without it, trajectory classification (and
            // therefore decay-based projection AND noisy-spike RED
            // suppression) has never actually run on a real device.
            val recentRates = org.aheadt1d.ratemath.RateMath.recentRates(ratePoints, count = 3)

            // RateConsensus's median of three independent rate estimates
            // (2-point slope, Kalman filter, linear regression) - used ONLY
            // to feed SeverityEngine's severity decision below, never the
            // displayed rate/arrow (see RawReading.severityRatePerMinute's
            // own doc).
            val severityRatePerMinute = org.aheadt1d.ratemath.RateConsensus
                .consensusRate(org.aheadt1d.ratemath.RateConsensus.vote(ratePoints))

            // How long the CURRENT low/high excursion has actually been
            // running - feeds TreatmentEffectWindow's asymmetric 30-min-low/
            // 90-min-high treatment-effect trust window. 125 mg/dL matches
            // AlertCoordinator's own YELLOW_MID_POINT ("roughly the middle
            // of the 70-180 healthy band") - same concept, same number, not
            // a new one invented here.
            val isLowSide = latest.sgv < 125
            val excursionDurationMinutes = org.aheadt1d.ratemath.TreatmentEffectWindow
                .excursionDurationMinutes(ratePoints, isLow = isLowSide)

            LatestTrendRepository.updateRawReading(
                context,
                RawReading(
                    value = latest.sgv,
                    time = latest.time.toEpochMilli(),
                    ratePerMinute = HealthConnectManager.calculateRatePerMinute(points),
                    deltaFromPrevious = HealthConnectManager.calculateDelta(points),
                    // Only true when THIS specific latest point is the one the
                    // fallback supplied - if Health Connect's own read already
                    // had something newer than the fallback (shouldn't happen
                    // given the consumeIfNewerThan gate above, but not assumed),
                    // this stays false rather than mislabeling a real HC point.
                    wasBroadcastSupplemented = usedBroadcastFallback && latest.time.toEpochMilli() == fallback?.timestampMillis,
                    recoveringFromLow = recoveringFromLow,
                    recentRates = recentRates,
                    severityRatePerMinute = severityRatePerMinute,
                    excursionDurationMinutes = excursionDurationMinutes
                )
            )
        }

        // Best-effort, fully isolated from everything below (see
        // UploadCoordinator's class doc) - piggybacks on this same read
        // rather than polling Health Connect a second time on its own timer.
        UploadCoordinator.maybeUpload(context, points)

        // Best-effort: keep the foreground service alive. When the service's own
        // loop is what called run(), this is a harmless no-op (it's already up);
        // when the Worker watchdog called it, this is the resurrection path.
        // Starting an FGS from a background context can throw on API 31+, which
        // ensureRunning swallows - it must never take down the writes above.
        GlucoseStatusService.ensureRunning(context)

        // Plateau/correction-response checks (PlateauCoordinator) are fully
        // independent of the backend rate-of-change engine below - a second,
        // separately-windowed Health Connect read (sized to whatever the
        // current tuning's HIGH_DURATION/escalation needs, not the fixed
        // 45-min WINDOW_MINUTES the backend call uses) so this can never be
        // limited by, or risk, the existing backend-call path.
        val plateauTuning = PlateauTuningPrefs.load(context)
        val plateauPoints = HealthConnectManager.readGlucosePoints(context, plateauTuning.lookbackMinutes())
        PlateauCoordinator.evaluate(context, plateauPoints, plateauTuning)

        if (points.size < 2) {
            Log.d(TAG, "Fewer than 2 points - skipping the backend call entirely")
            return Outcome.SUCCESS
        }

        // Feeds the backend's per-reading minutesSinceLastBolus (see
        // guess-engine.js's disabled bolus-dependent guesses) - a plain
        // epoch-millis lookup against the events table, not a dose value
        // (see UserEventRepository.mostRecentInsulinTimestamp's doc for why
        // that's out of scope). Best-effort: absent entirely rather than
        // failing the whole check if this one extra query throws.
        val lastBolusTimestamp = runCatching {
            UserEventRepository.mostRecentInsulinTimestamp(context)
        }.getOrNull()

        val body = JSONObject().apply {
            put("readings", JSONArray(points.map { point ->
                JSONObject().apply {
                    put("sgv", point.sgv)
                    put("date", point.time.toEpochMilli())
                }
            }))
            lastBolusTimestamp?.let { put("lastBolusTimestamp", it) }
            // Development-only overrides. Release builds leave backend defaults
            // authoritative; debug values are validated again on the server.
            if (BuildConfig.DEBUG) {
                val tuning = TuningPrefs.load(context)
                put("tuning", JSONObject().apply {
                    put("yellowProjectedLow", tuning.yellowProjectedLow)
                    put("yellowProjectedHigh", tuning.yellowProjectedHigh)
                    put("redProjectedLow", tuning.redProjectedLow)
                    put("redProjectedHigh", tuning.redProjectedHigh)
                    put("extendedProjectionMinutes", tuning.extendedProjectionMinutes)
                    put("smoothingIntervals", tuning.smoothingIntervals)
                })
            }
        }

        return try {
            val responseJson = BackendClient.postCheckTrend(context, body)
            // Dumps the full payload (glucose value, severity, rate, guesses) -
            // never in release, where a bugreport/logcat pull would otherwise
            // expose real health data.
            if (BuildConfig.DEBUG) Log.d(TAG, "check-trend response: $responseJson")
            updateLatestTrend(context, responseJson)
            Outcome.SUCCESS
        } catch (e: IOException) {
            Log.w(TAG, "check-trend POST failed, will retry", e)
            Outcome.RETRY
        }
    }

    /**
     * Health Connect is the ONLY source (2026-08-01: the Nightscout fallback
     * that used to step in here was removed - it read from the same web
     * endpoint ahead-dashboard's viewer reads from, with no staleness check
     * before that data was fed straight into the raw-reading repository and
     * on to the backend's RED/YELLOW classification. That endpoint had
     * already shown a 30+-minute-old reading while Health Connect correctly
     * had live data - a stale substitute silently scored as current could
     * either suppress a real alert or fire a false one with no indication to
     * the user that the number wasn't fresh. When Health Connect can't be
     * read at all (not installed/unsupported, permission revoked, or a
     * transient RemoteException), this now returns null unconditionally, so
     * the app surfaces its existing signal-lost/stale state instead of
     * silently substituting a possibly-stale number.
     */
    private suspend fun readPoints(context: Context): List<GlucosePoint>? {
        val healthConnectClient = HealthConnectManager.getClientOrNull(context)
        if (healthConnectClient == null) {
            Log.w(TAG, "Health Connect client unavailable (not installed, or SDK unsupported on this device)")
            LatestTrendRepository.updateReadBlocked(ReadBlockedReason.HC_UNAVAILABLE)
            return null
        }

        val granted = healthConnectClient.permissionController.getGrantedPermissions()
        if (!granted.containsAll(HealthConnectManager.ALL_PERMISSIONS)) {
            // Most commonly missing READ_HEALTH_DATA_IN_BACKGROUND specifically -
            // a background execution context doesn't count as foreground to
            // Health Connect, even when kicked off via the "Check now" button.
            Log.w(TAG, "Missing a Health Connect permission (have $granted, need ${HealthConnectManager.ALL_PERMISSIONS})")
            LatestTrendRepository.updateReadBlocked(ReadBlockedReason.PERMISSION_MISSING)
            return null
        }

        val points = try {
            HealthConnectManager.readGlucosePoints(context, WINDOW_MINUTES)
        } catch (e: RemoteException) {
            // Transient read error with permissions verified granted this run.
            Log.w(TAG, "Health Connect read failed despite granted permissions", e)
            LatestTrendRepository.updateReadBlocked(null)
            return null
        }

        Log.d(TAG, "Read ${points.size} Health Connect point(s) in the last $WINDOW_MINUTES min")
        return points
    }

    /** Persists + surfaces only the newest scored reading from this run's response. */
    private fun updateLatestTrend(context: Context, responseJson: JSONObject) {
        val processed = responseJson.optJSONArray("processed") ?: return
        if (processed.length() == 0) {
            Log.d(TAG, "Backend returned no new processed readings (already seen, or dedup'd)")
            return
        }

        val latest = processed.getJSONObject(processed.length() - 1)
        if (!latest.has("currentValue") || !latest.has("severity")) {
            // Payload may still contain a partial glucose value even though
            // it's malformed - keep the release-build warning breadcrumb, but
            // only dump the raw JSON in debug.
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Backend's newest processed reading is missing currentValue/severity - dropping this trend update: $latest")
            } else {
                Log.w(TAG, "Backend's newest processed reading is missing currentValue/severity - dropping this trend update")
            }
            return
        }

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Publishing trend: currentValue=${latest.optInt("currentValue")} severity=${latest.optString("severity")}")
        }

        val trend = LatestTrend(
            currentValue = latest.getInt("currentValue"),
            severity = latest.getString("severity"),
            rate = if (latest.has("rate") && !latest.isNull("rate")) latest.getDouble("rate") else null,
            projected = if (latest.has("projected") && !latest.isNull("projected")) latest.getInt("projected") else null,
            projectedExtended = if (latest.has("projectedExtended") && !latest.isNull("projectedExtended")) latest.getInt("projectedExtended") else null,
            date = latest.getLong("date"),
            guesses = parseGuesses(latest.optJSONArray("guesses"))
        )

        LatestTrendRepository.update(context, trend)
    }

    /** Parses optional, event-only hypotheses without allowing a malformed
     *  backend payload to discard the actual trend result. */
    private fun parseGuesses(array: JSONArray?): List<Guess> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val label = item.optString("label").trim()
                if (label.isNotEmpty()) add(Guess(label, item.optString("confidence", "low")))
            }
        }
    }
}
