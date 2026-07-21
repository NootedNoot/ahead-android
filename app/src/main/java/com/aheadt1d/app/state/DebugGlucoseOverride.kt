package com.aheadt1d.app.state

import com.aheadt1d.app.health.GlucosePoint

/**
 * Debug-only in-memory override for the chart's data source. Lives in the
 * main source set (not app/src/debug) so HealthConnectManager can check it
 * without a debug-only reference, but every read/write site gates on
 * BuildConfig.DEBUG - in release builds nothing ever sets [points], so this
 * is permanently inert. Deliberately never touches the real Health Connect
 * store: swapping the in-app data source instead of writing synthetic
 * records into a system-wide health data store keeps injected test data
 * fully reversible (gone on process death, invisible to any other app).
 */
object DebugGlucoseOverride {
    @Volatile
    var points: List<GlucosePoint>? = null
        private set

    fun setPoints(newPoints: List<GlucosePoint>) {
        points = newPoints.sortedBy { it.time }
    }

    fun clear() {
        points = null
    }

    val isActive: Boolean get() = points != null
}
