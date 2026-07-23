package com.aheadt1d.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aheadt1d.app.alerts.CheckNowSuppression
import com.aheadt1d.app.state.AppForegroundTracker

/**
 * WorkManager entry point for the glucose check. Two roles:
 *  - The manual "Check now" one-time job (WorkScheduler.runOnce), tagged
 *    KEY_MANUAL_CHECK.
 *  - The periodic WATCHDOG (WorkScheduler.schedulePeriodic, ~15-min floor): the
 *    primary 5-minute cadence now lives in GlucoseStatusService's own loop, so
 *    this periodic run exists to resurrect that service if an aggressive OEM
 *    kills it, and to provide a background catch-up when the app is closed.
 *
 * The actual pipeline lives in GlucoseCheckRunner, shared with the service loop,
 * so both run the identical proven chain. This class only translates the run
 * outcome into a WorkManager Result and handles the manual-check suppression flag.
 */
class GlucoseCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // A manual "Check now" tap while the app is in the foreground shouldn't
        // re-trigger the interruptive notification/voice alert for a severity the
        // user is already looking at on screen. Real periodic background runs
        // never set KEY_MANUAL_CHECK, so this only ever suppresses that one path -
        // a genuine trend-detector crossing found by the periodic watchdog (or by
        // the foreground service loop, which never marks suppression) still alerts
        // normally regardless of foreground state.
        if (inputData.getBoolean(KEY_MANUAL_CHECK, false) && AppForegroundTracker.isForeground) {
            CheckNowSuppression.markSuppressed()
        }

        return when (GlucoseCheckRunner.run(applicationContext)) {
            GlucoseCheckRunner.Outcome.SUCCESS -> Result.success()
            GlucoseCheckRunner.Outcome.RETRY -> Result.retry()
            GlucoseCheckRunner.Outcome.FAILURE -> Result.failure()
        }
    }

    companion object {
        const val KEY_MANUAL_CHECK = "manual_check"
    }
}
