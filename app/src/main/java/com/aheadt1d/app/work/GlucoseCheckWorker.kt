package com.aheadt1d.app.work

import android.content.Context
import android.os.RemoteException
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aheadt1d.app.BuildConfig
import com.aheadt1d.app.alerts.CheckNowSuppression
import com.aheadt1d.app.alerts.PlateauCoordinator
import com.aheadt1d.app.health.HealthConnectManager
import com.aheadt1d.app.network.BackendClient
import com.aheadt1d.app.notifications.GlucoseStatusService
import com.aheadt1d.app.state.AppForegroundTracker
import com.aheadt1d.app.state.LatestTrend
import com.aheadt1d.app.state.LatestTrendRepository
import com.aheadt1d.app.state.RawReading
import com.aheadt1d.app.state.Guess
import com.aheadt1d.app.tuning.PlateauTuningPrefs
import com.aheadt1d.app.tuning.TuningPrefs
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs on WorkManager's ~15-min minimum periodic interval (true 5-min periodic
 * work isn't available on Android). Each run re-reads the trailing window
 * rather than "just what's new" - the backend's trend detector expects the
 * full recent readings array anyway, so a wider window costs nothing and
 * covers CGM syncs that land between runs.
 */
class GlucoseCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Marked before any repository writes below: a manual "Check now" tap
        // while the app is in the foreground shouldn't re-trigger the
        // interruptive notification/voice alert for a severity the user is
        // already looking at on screen. Real periodic background runs never
        // set KEY_MANUAL_CHECK, so this only ever suppresses this one path -
        // a genuine trend-detector crossing found by the periodic schedule
        // still alerts normally regardless of foreground state.
        if (inputData.getBoolean(KEY_MANUAL_CHECK, false) && AppForegroundTracker.isForeground) {
            CheckNowSuppression.markSuppressed()
        }

        val healthConnectClient = HealthConnectManager.getClientOrNull(applicationContext) ?: run {
            Log.w(TAG, "Health Connect client unavailable (not installed, or SDK unsupported on this device)")
            return Result.failure()
        }

        val granted = healthConnectClient.permissionController.getGrantedPermissions()
        if (!granted.containsAll(HealthConnectManager.ALL_PERMISSIONS)) {
            // Most commonly missing READ_HEALTH_DATA_IN_BACKGROUND specifically -
            // WorkManager's execution context doesn't count as foreground to
            // Health Connect, even when kicked off via the "Check now" button.
            Log.w(TAG, "Missing a Health Connect permission (have $granted, need ${HealthConnectManager.ALL_PERMISSIONS})")
            return Result.failure()
        }

        val points = try {
            HealthConnectManager.readGlucosePoints(applicationContext, WINDOW_MINUTES)
        } catch (e: RemoteException) {
            Log.w(TAG, "Health Connect read failed despite granted permissions", e)
            return Result.failure()
        }

        Log.d(TAG, "Read ${points.size} Health Connect point(s) in the last $WINDOW_MINUTES min")

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
                applicationContext,
                RawReading(
                    value = latest.sgv,
                    time = latest.time.toEpochMilli(),
                    ratePerMinute = HealthConnectManager.calculateRatePerMinute(points),
                    deltaFromPrevious = HealthConnectManager.calculateDelta(points)
                )
            )
        }

        // GlucoseStatusService owns the ongoing notification end-to-end by
        // observing LatestTrendRepository - kept running so that holds even
        // on runs where the backend never has anything new to say. Deliberately
        // last and best-effort: starting a foreground service from a Worker can
        // throw (e.g. ForegroundServiceStartNotAllowedException on API 31+ if
        // this run isn't considered foreground-eligible), and that must never
        // take down the repository updates above with it.
        GlucoseStatusService.ensureRunning(applicationContext)

        // Plateau/correction-response checks (PlateauCoordinator) are fully
        // independent of the backend rate-of-change engine below - a second,
        // separately-windowed Health Connect read (sized to whatever the
        // current tuning's HIGH_DURATION/escalation needs, not the fixed
        // 45-min WINDOW_MINUTES the backend call uses) so this can never be
        // limited by, or risk, the existing backend-call path.
        val plateauTuning = PlateauTuningPrefs.load(applicationContext)
        val plateauPoints = HealthConnectManager.readGlucosePoints(applicationContext, plateauTuning.lookbackMinutes())
        PlateauCoordinator.evaluate(applicationContext, plateauPoints, plateauTuning)

        if (points.size < 2) {
            Log.d(TAG, "Fewer than 2 points - skipping the backend call entirely")
            return Result.success()
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
                val tuning = TuningPrefs.load(applicationContext)
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
            updateLatestTrend(responseJson)
            Result.success()
        } catch (e: IOException) {
            Log.w(TAG, "check-trend POST failed, will retry", e)
            Result.retry()
        }
    }

    /** Persists + surfaces only the newest scored reading from this run's response. */
    private fun updateLatestTrend(responseJson: JSONObject) {
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

        LatestTrendRepository.update(applicationContext, trend)
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

    companion object {
        private const val WINDOW_MINUTES = 45L
        private const val TAG = "GlucoseCheckWorker"
        const val KEY_MANUAL_CHECK = "manual_check"
    }
}
