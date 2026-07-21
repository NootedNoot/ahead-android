package com.aheadt1d.app.state

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Lightweight foreground tracker via Activity lifecycle callbacks, rather
 * than pulling in the separate androidx.lifecycle:lifecycle-process artifact
 * (ProcessLifecycleOwner) for a single boolean. Counts started activities -
 * a brief lag between "user backgrounds the app" and this flipping to false
 * is expected (ProcessLifecycleOwner itself debounces the same way) and is
 * fine here since this only gates a UX-only alert-suppression decision, not
 * anything safety-critical.
 */
object AppForegroundTracker : Application.ActivityLifecycleCallbacks {
    private var startedCount = 0

    val isForeground: Boolean get() = startedCount > 0

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        startedCount++
    }

    override fun onActivityStopped(activity: Activity) {
        startedCount = (startedCount - 1).coerceAtLeast(0)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
