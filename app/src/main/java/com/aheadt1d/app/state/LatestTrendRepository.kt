package com.aheadt1d.app.state

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide holder for the latest trend result. GlucoseCheckWorker runs
 * in this same process and publishes here after every run; MainActivity
 * observes this Flow instead of polling storage. Backed by LatestTrendStore
 * so the value survives the app process being killed and restarted -
 * AheadApplication seeds it from disk on process start.
 */
object LatestTrendRepository {

    private val _latestTrend = MutableStateFlow<LatestTrend?>(null)
    val latestTrend: StateFlow<LatestTrend?> = _latestTrend.asStateFlow()

    // Independent of _latestTrend - see RawReading's doc comment. Updated by
    // GlucoseCheckWorker from the raw Health Connect read on every run,
    // whether or not the backend call happens or succeeds.
    private val _latestRawReading = MutableStateFlow<RawReading?>(null)
    val latestRawReading: StateFlow<RawReading?> = _latestRawReading.asStateFlow()

    // Fires on every successful Health Connect read the Worker does, whether
    // or not the backend had anything new to say about it. The chart/number
    // in MainActivity read Health Connect directly, not through the backend -
    // this is what tells them "go re-read now" without depending on a trend
    // result existing.
    private val _lastCheckedAt = MutableStateFlow(0L)
    val lastCheckedAt: StateFlow<Long> = _lastCheckedAt.asStateFlow()

    fun init(context: Context) {
        _latestTrend.value = LatestTrendStore.load(context.applicationContext)
        _latestRawReading.value = RawReadingStore.load(context.applicationContext)
    }

    fun update(context: Context, trend: LatestTrend) {
        LatestTrendStore.save(context.applicationContext, trend)
        _latestTrend.value = trend
    }

    fun updateRawReading(context: Context, reading: RawReading) {
        RawReadingStore.save(context.applicationContext, reading)
        _latestRawReading.value = reading
    }

    fun markChecked() {
        _lastCheckedAt.value = System.currentTimeMillis()
    }
}
