package com.aheadt1d.app.work

import android.content.Context
import java.util.concurrent.TimeUnit
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf

object WorkScheduler {

    private const val UNIQUE_PERIODIC_WORK_NAME = "glucose_check_periodic"
    private const val UNIQUE_ONE_TIME_WORK_NAME = "glucose_check_once"

    // 15 minutes is WorkManager's enforced minimum for PeriodicWorkRequest.
    private const val INTERVAL_MINUTES = 15L

    fun schedulePeriodic(context: Context) {
        // Deliberately no network constraint: the Health Connect read (which
        // drives the live glucose number and the persistent notification) is
        // entirely on-device and needs no connectivity. Gating the whole
        // Worker run on NetworkType.CONNECTED meant the on-device read - not
        // just the optional backend trend call - silently stopped happening
        // whenever the phone had no/flaky internet, which is exactly when you
        // don't want "live" data to freeze. The backend POST inside doWork()
        // already handles being offline on its own via Result.retry().
        val request = PeriodicWorkRequestBuilder<GlucoseCheckWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.LINEAR, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()

        // UPDATE (not KEEP): devices upgrading from a build that scheduled this
        // with the old NetworkType.CONNECTED constraint need that constraint
        // actually removed, not left in place forever because a periodic work
        // already existed under this name. UPDATE swaps in the new definition
        // without resetting the existing schedule's next-run time.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /**
     * Fires the check immediately, bypassing the periodic schedule - handy for testing.
     * Deduped by name (REPLACE) so mashing the "Check now" button doesn't pile up a
     * stack of concurrent Worker runs - just the latest tap actually runs.
     *
     * Tagged with GlucoseCheckWorker.KEY_MANUAL_CHECK so the Worker (and, via
     * CheckNowSuppression, AlertCoordinator) can tell this run apart from a
     * true periodic background check - every current call site (this button,
     * TuningActivity) is a manual, foreground-initiated tap.
     */
    fun runOnce(context: Context) {
        val request = OneTimeWorkRequestBuilder<GlucoseCheckWorker>()
            .setInputData(workDataOf(GlucoseCheckWorker.KEY_MANUAL_CHECK to true))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
