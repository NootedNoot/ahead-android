package com.aheadt1d.app.alerts

/**
 * Short-lived marker set when a manual "Check Now" tap resolves while the
 * app is foregrounded, so AlertCoordinator can skip the interruptive
 * notification/voice treatment for the resulting update - the user already
 * sees the new value/severity on screen, so re-alerting is redundant.
 *
 * A window rather than a synchronous flag because GlucoseCheckWorker and
 * AlertCoordinator.evaluate() are decoupled: the Worker writes to
 * LatestTrendRepository, and GlucoseStatusService reacts to that
 * asynchronously moments later via its StateFlow combine - there's no single
 * call stack to thread an explicit parameter through, so this expires on its
 * own instead of needing to be consumed/cleared by the reader.
 */
object CheckNowSuppression {
    private const val WINDOW_MS = 10_000L

    @Volatile
    private var suppressUntilMs = 0L

    fun markSuppressed() {
        suppressUntilMs = System.currentTimeMillis() + WINDOW_MS
    }

    fun isSuppressed(): Boolean = System.currentTimeMillis() < suppressUntilMs
}
