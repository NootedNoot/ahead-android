package com.aheadt1d.app.events

import com.aheadt1d.app.events.EventSyncPlanner.Apply
import com.aheadt1d.app.events.EventSyncPlanner.Local
import com.aheadt1d.app.events.EventSyncPlanner.Remote
import com.aheadt1d.app.events.EventSyncPlanner.SyncedState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventSyncPlannerTest {

    private val install = "inst1"
    private fun local(id: Long, note: String? = "note $id", glucose: Float? = 120f, tag: String = "correction") =
        Local(id = id, timestamp = 1_700_000_000_000L + id, tag = tag, note = note, glucoseAtTime = glucose)
    private fun synced(vararg events: Local) =
        events.associate { it.id to SyncedState(EventSyncPlanner.clientIdFor(install, it.id), EventSyncPlanner.fingerprint(it)) }
    private fun remoteOf(e: Local, clientId: String = EventSyncPlanner.clientIdFor(install, e.id), deleted: Boolean = false) =
        Remote(clientId, e.timestamp, e.tag, e.note, e.glucoseAtTime, deleted)

    @Test
    fun `first sync pushes every local event with a stable client id`() {
        val pushes = EventSyncPlanner.planPushes(listOf(local(1), local(2)), emptyMap(), install)
        assertEquals(listOf("android-inst1-1", "android-inst1-2"), pushes.map { it.clientId })
        assertTrue(pushes.none { it.deleted })
    }

    @Test
    fun `nothing changed means nothing to push`() {
        val events = listOf(local(1), local(2))
        assertTrue(EventSyncPlanner.planPushes(events, synced(*events.toTypedArray()), install).isEmpty())
    }

    @Test
    fun `an edited note is pushed under its existing client id`() {
        val before = local(1)
        val after = before.copy(note = "actually it was a site issue")
        val pushes = EventSyncPlanner.planPushes(listOf(after), synced(before), install)
        assertEquals(1, pushes.size)
        assertEquals("android-inst1-1", pushes[0].clientId)
        assertEquals("actually it was a site issue", pushes[0].note)
        assertEquals(EventSyncPlanner.fingerprint(after), pushes[0].fingerprint)
    }

    @Test
    fun `a locally deleted event is pushed as a tombstone with no note`() {
        val gone = local(7)
        val pushes = EventSyncPlanner.planPushes(emptyList(), synced(gone), install)
        assertEquals(1, pushes.size)
        assertTrue(pushes[0].deleted)
        assertEquals("android-inst1-7", pushes[0].clientId)
        assertEquals(null, pushes[0].note)
    }

    @Test
    fun `float noise from JSON round trips is not an edit`() {
        val e = local(1, glucose = 245f)
        val viaJson = e.copy(glucoseAtTime = 245.00001f)
        assertEquals(EventSyncPlanner.fingerprint(e), EventSyncPlanner.fingerprint(viaJson))
    }

    @Test
    fun `fingerprint never contains the note text`() {
        val fp = EventSyncPlanner.fingerprint(local(1, note = "very private thing"))
        assertFalse(fp.contains("private"))
        assertEquals(64, fp.length)
    }

    @Test
    fun `echo of our own push is a no-op`() {
        val e = local(1)
        val actions = EventSyncPlanner.planApply(listOf(remoteOf(e)), listOf(e), synced(e))
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `an event logged on the web is inserted locally`() {
        val web = Remote("web-abc", 1_700_000_100_000L, "meal", "tacos", 150f, deleted = false)
        val actions = EventSyncPlanner.planApply(listOf(web), emptyList(), emptyMap())
        assertEquals(listOf<Apply>(Apply.Insert(web)), actions)
    }

    @Test
    fun `a deleted event we never had is ignored`() {
        val web = Remote("web-abc", 1L, "meal", null, null, deleted = true)
        assertTrue(EventSyncPlanner.planApply(listOf(web), emptyList(), emptyMap()).isEmpty())
    }

    @Test
    fun `an edit made on the web updates the local row`() {
        val e = local(1)
        val edited = remoteOf(e).copy(note = "edited on portal")
        val actions = EventSyncPlanner.planApply(listOf(edited), listOf(e), synced(e))
        assertEquals(listOf<Apply>(Apply.Update(1, edited)), actions)
    }

    @Test
    fun `a delete made on the web deletes the local row`() {
        val e = local(1)
        val actions = EventSyncPlanner.planApply(listOf(remoteOf(e, deleted = true)), listOf(e), synced(e))
        assertEquals(listOf<Apply>(Apply.Delete(1)), actions)
    }

    @Test
    fun `a remote update for a row we already deleted locally does not resurrect it`() {
        val e = local(1)
        val actions = EventSyncPlanner.planApply(listOf(remoteOf(e).copy(note = "x")), emptyList(), synced(e))
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `only the latest version of an event in one batch is applied`() {
        val e = local(1)
        val v1 = remoteOf(e).copy(note = "v1")
        val v2 = remoteOf(e).copy(note = "v2")
        val actions = EventSyncPlanner.planApply(listOf(v1, v2), listOf(e), synced(e))
        assertEquals(listOf<Apply>(Apply.Update(1, v2)), actions)
    }
}
