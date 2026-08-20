package com.aheadt1d.app.bridge

/**
 * Holds at most one pending reading from AheadBLE V3's direct broadcast
 * (see GlucoseBroadcastReceiver), for GlucoseCheckRunner to consume as an
 * unconfirmed fallback ONLY if Health Connect's own read doesn't already
 * cover it. In-memory only, deliberately not persisted - a value sitting
 * here across a process restart with no way to re-verify it against a fresh
 * Health Connect read would be exactly the kind of silently-stale substitute
 * this app's own history (see GlucoseCheckRunner's readPoints doc, and the
 * removed NightscoutFallbackClient) has already been burned by.
 */
object BroadcastGlucoseBuffer {

    data class PendingReading(
        val mgDl: Int,
        val timestampMillis: Long,
        val trendRate: Double?,
        val receivedAtMillis: Long,
    )

    @Volatile
    private var pending: PendingReading? = null

    fun offer(reading: PendingReading) {
        pending = reading
    }

    /**
     * Returns and CONSUMES (clears) the pending reading, but only if it's
     * actually newer than [latestKnownEpochMillis] - i.e. genuinely missing
     * from the caller's own Health Connect read, not a duplicate of
     * something already there. Consuming on read (not just on match) means a
     * reading that Health Connect already has by the time this is checked
     * gets silently dropped here too, rather than sitting around to be
     * wrongly reinjected on some future cycle once it's no longer the latest
     * data.
     */
    @Synchronized
    fun consumeIfNewerThan(latestKnownEpochMillis: Long?): PendingReading? {
        val candidate = pending ?: return null
        pending = null
        if (latestKnownEpochMillis != null && candidate.timestampMillis <= latestKnownEpochMillis) {
            return null
        }
        return candidate
    }
}
