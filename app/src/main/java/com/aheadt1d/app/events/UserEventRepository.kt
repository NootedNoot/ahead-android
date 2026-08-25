package com.aheadt1d.app.events

import android.content.Context
import com.aheadt1d.app.state.LatestTrendRepository
import kotlinx.coroutines.flow.Flow

/**
 * Thin wrapper around AppDatabase.userEventDao(), following the same
 * "object + context-per-call" shape as LatestTrendRepository rather than
 * introducing a DI framework for one table.
 */
object UserEventRepository {

    private fun dao(context: Context) = AppDatabase.getInstance(context).userEventDao()

    /**
     * Logs an event and stamps it with a glucose reading. By default that's
     * the *current* reading, pulled from LatestTrendRepository.latestRawReading
     * - the same on-device, Health-Connect-backed single source of truth the
     * status notification and MainActivity's big number read from. Null when
     * no reading has arrived yet rather than a stale/fabricated value.
     *
     * glucoseOverride exists for backdating an event to a specific chart
     * point (see GraphActivity's long-press-on-point flow): in that case the
     * live reading would be wrong for the historical timestamp being logged,
     * so the tapped point's own value is passed in instead.
     */
    suspend fun log(
        context: Context,
        tag: EventTag,
        note: String? = null,
        timestamp: Long = System.currentTimeMillis(),
        glucoseOverride: Float? = null
    ): Long {
        val currentGlucose = glucoseOverride ?: LatestTrendRepository.latestRawReading.value?.value?.toFloat()
        return dao(context).insert(
            UserEvent(
                timestamp = timestamp,
                tag = tag.storageValue,
                note = note?.takeIf { it.isNotBlank() },
                glucoseAtTime = currentGlucose
            )
        )
    }

    /** Overwrites tag/note on an already-logged event - id/timestamp/
     *  glucoseAtTime are untouched, since editing what happened shouldn't
     *  rewrite when it happened or what the glucose reading was. */
    suspend fun updateEvent(context: Context, event: UserEvent, tag: EventTag, note: String?) {
        dao(context).update(event.copy(tag = tag.storageValue, note = note?.takeIf { it.isNotBlank() }))
    }

    suspend fun deleteEvent(context: Context, event: UserEvent) = dao(context).delete(event)

    fun allEvents(context: Context): Flow<List<UserEvent>> = dao(context).getAll()

    fun eventsInRange(context: Context, startMillis: Long, endMillis: Long): Flow<List<UserEvent>> =
        dao(context).getInRange(startMillis, endMillis)

    /**
     * Epoch millis of the most recent logged INSULIN event at or before
     * [beforeMillis], or null if none exists yet. Feeds
     * GlucoseCheckRunner's lastBolusTimestamp field, which the backend turns
     * into a per-reading minutesSinceLastBolus - see
     * ahead-backend/guess-engine.js's disabled bolus-dependent guess blocks,
     * which this unlocks. Deliberately timestamp-only, no dose amount (out
     * of scope for v1 - would need a Room schema migration, and nothing
     * currently disabled needs more than timing).
     */
    suspend fun mostRecentInsulinTimestamp(context: Context, beforeMillis: Long = System.currentTimeMillis()): Long? =
        dao(context).getMostRecentByTag(EventTag.INSULIN.storageValue, beforeMillis)?.timestamp
}
