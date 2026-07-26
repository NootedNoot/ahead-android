package com.aheadt1d.app.work

import android.content.Context
import android.os.RemoteException
import android.util.Log
import com.aheadt1d.app.BuildConfig
import com.aheadt1d.app.alerts.PlateauCoordinator
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
import java.io.IOException
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

    suspend fun run(context: Context): Outcome {
        // Each early return below records WHY the read pipeline is blocked
        // (or clears the diagnosis on success). The stale-state copy keys off
        // this so an app-side cause - revoked permission, missing Health
        // Connect - is reported as such instead of "check your CGM".
        val healthConnectClient = HealthConnectManager.getClientOrNull(context) ?: run {
            Log.w(TAG, "Health Connect client unavailable (not installed, or SDK unsupported on this device)")
            LatestTrendRepository.updateReadBlocked(ReadBlockedReason.HC_UNAVAILABLE)
            return Outcome.FAILURE
        }

        val granted = healthConnectClient.permissionController.getGrantedPermissions()
        if (!granted.containsAll(HealthConnectManager.ALL_PERMISSIONS)) {
            // Most commonly missing READ_HEALTH_DATA_IN_BACKGROUND specifically -
            // a background execution context doesn't count as foreground to
            // Health Connect, even when kicked off via the "Check now" button.
            Log.w(TAG, "Missing a Health Connect permission (have $granted, need ${HealthConnectManager.ALL_PERMISSIONS})")
            LatestTrendRepository.updateReadBlocked(ReadBlockedReason.PERMISSION_MISSING)
            return Outcome.FAILURE
        }

        val points = try {
            HealthConnectManager.readGlucosePoints(context, WINDOW_MINUTES)
        } catch (e: RemoteException) {
            // Transient read error with permissions verified granted this run -
            // clear any older app-side diagnosis rather than letting it linger
            // past the condition it described; the generic gap copy applies.
            Log.w(TAG, "Health Connect read failed despite granted permissions", e)
            LatestTrendRepository.updateReadBlocked(null)
            return Outcome.FAILURE
        }

        Log.d(TAG, "Read ${points.size} Health Connect point(s) in the last $WINDOW_MINUTES min")
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
            LatestTrendRepository.updateRawReading(
                context,
                RawReading(
                    value = latest.sgv,
                    time = latest.time.toEpochMilli(),
                    ratePerMinute = HealthConnectManager.calculateRatePerMinute(points),
                    deltaFromPrevious = HealthConnectManager.calculateDelta(points)
                )
            )
        }

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

        val body = JSONObject().apply {
            put("readings", JSONArray(points.map { point ->
                JSONObject().apply {
                    put("sgv", point.sgv)
                    put("date", point.time.toEpochMilli())
                }
            }))
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
            val responseJson = BackendClient.postCheckTrend(body)
            Log.d(TAG, "check-trend response: $responseJson")
            updateLatestTrend(context, responseJson)
            Outcome.SUCCESS
        } catch (e: IOException) {
            Log.w(TAG, "check-trend POST failed, will retry", e)
            Outcome.RETRY
        }
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
            Log.w(TAG, "Backend's newest processed reading is missing currentValue/severity - dropping this trend update: $latest")
            return
        }

        Log.d(TAG, "Publishing trend: currentValue=${latest.optInt("currentValue")} severity=${latest.optString("severity")}")

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
