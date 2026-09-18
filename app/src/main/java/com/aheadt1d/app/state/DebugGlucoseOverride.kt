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
    const val DISCLAIMER = "🚨 * INJECTED TEST DATA ACTIVE * (NOT REAL CGM DATA) 🚨"
    const val DISCLAIMER_SHORT = "🚨 INJECTED TEST DATA (NOT REAL)"
    const val TITLE_PREFIX = "[INJECTED] "
    const val BODY_PREFIX = "[INJECTED TEST DATA] "

    const val ACTION_DEBUG_STATE_CHANGED = "com.aheadt1d.app.DEBUG_STATE_CHANGED"

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

    fun notifyStateChanged(context: android.content.Context) {
        val intent = android.content.Intent(ACTION_DEBUG_STATE_CHANGED).apply {
            putExtra("isActive", isActive)
            putExtra("disclaimer", DISCLAIMER)
            putExtra("disclaimerShort", DISCLAIMER_SHORT)
        }
        context.sendBroadcast(intent)
    }
}
