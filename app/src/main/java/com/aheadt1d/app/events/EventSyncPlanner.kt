package com.aheadt1d.app.events

import java.security.MessageDigest
import kotlin.math.roundToInt

/**
 * Pure decision logic for syncing the local `user_events` table with
 * ahead-backend's /api/events/sync (see ahead-backend/routes/events.js for
 * the server half). No Android, Room or network types on purpose, so every
 * rule here is plain-JVM unit-testable; [EventSync] does the I/O around it.
 *
 * The Room table itself is untouched (no migration - a bad migration would
 * crash the app that fires alerts). Instead, which local row corresponds to
 * which server event, and what it looked like when last synced, lives in a
 * small side map: localId -> [SyncedState]. A row is pushed when its
 * fingerprint no longer matches what was last synced; a mapped row that has
 * disappeared locally is pushed as a delete.
 */
object EventSyncPlanner {

    data class Local(
        val id: Long,
        val timestamp: Long,
        val tag: String,
        val note: String?,
        val glucoseAtTime: Float?
    )

    data class Remote(
        val clientId: String,
        val timestamp: Long,
        val tag: String,
        val note: String?,
        val glucoseAtTime: Float?,
        val deleted: Boolean
    )

    data class SyncedState(val clientId: String, val fingerprint: String)

    data class Push(
        val localId: Long,
        val clientId: String,
        val timestamp: Long,
        val tag: String,
        val note: String?,
        val glucoseAtTime: Float?,
        val deleted: Boolean,
        /** What to record as synced once the server accepts this push. */
        val fingerprint: String
    )

    sealed class Apply {
        data class Insert(val remote: Remote) : Apply()
        data class Update(val localId: Long, val remote: Remote) : Apply()
        data class Delete(val localId: Long) : Apply()
    }

    fun clientIdFor(installId: String, localId: Long): String = "android-$installId-$localId"

    /** SHA-256 of the event's content, so the side map never holds note
     *  text in the clear. Glucose is rounded so a Float that went through
     *  JSON and back can never look like an edit. */
    fun fingerprint(timestamp: Long, tag: String, note: String?, glucoseAtTime: Float?): String {
        val raw = "$timestamp|$tag|${note.orEmpty()}|${glucoseAtTime?.roundToInt() ?: ""}"
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun fingerprint(e: Local): String = fingerprint(e.timestamp, e.tag, e.note, e.glucoseAtTime)
    fun fingerprint(e: Remote): String = fingerprint(e.timestamp, e.tag, e.note, e.glucoseAtTime)

    /** Everything that changed locally since the last successful sync. */
    fun planPushes(local: List<Local>, synced: Map<Long, SyncedState>, installId: String): List<Push> {
        val pushes = ArrayList<Push>()
        val localIds = HashSet<Long>()
        for (e in local) {
            localIds += e.id
            val state = synced[e.id]
            val fp = fingerprint(e)
            if (state == null || state.fingerprint != fp) {
                pushes += Push(
                    localId = e.id,
                    clientId = state?.clientId ?: clientIdFor(installId, e.id),
                    timestamp = e.timestamp, tag = e.tag, note = e.note, glucoseAtTime = e.glucoseAtTime,
                    deleted = false, fingerprint = fp
                )
            }
        }
        for ((localId, state) in synced) {
            if (localId !in localIds) {
                pushes += Push(
                    localId = localId, clientId = state.clientId,
                    timestamp = 0L, tag = "other", note = null, glucoseAtTime = null,
                    deleted = true, fingerprint = ""
                )
            }
        }
        return pushes
    }

    /**
     * What to do locally with the server's changes. Remote rows arrive after
     * our own pushes were applied server-side (same request), so they are the
     * final word - including echoes of our own pushes, which are no-ops here
     * because their fingerprint already matches.
     */
    fun planApply(remote: List<Remote>, local: List<Local>, synced: Map<Long, SyncedState>): List<Apply> {
        val localById = local.associateBy { it.id }
        val localIdByClientId = HashMap<String, Long>()
        for ((localId, state) in synced) localIdByClientId[state.clientId] = localId

        val actions = ArrayList<Apply>()
        // Only the last version of each event in this batch matters.
        val latest = LinkedHashMap<String, Remote>()
        for (r in remote) latest[r.clientId] = r
        for (r in latest.values) {
            val localId = localIdByClientId[r.clientId]
            val existing = localId?.let { localById[it] }
            when {
                localId != null && r.deleted -> actions += Apply.Delete(localId)
                existing != null && fingerprint(existing) != fingerprint(r) -> actions += Apply.Update(existing.id, r)
                existing != null -> Unit
                // Mapped but already gone locally: the pending local delete
                // wins on the next push; don't resurrect it.
                localId != null -> Unit
                !r.deleted -> actions += Apply.Insert(r)
            }
        }
        return actions
    }
}
