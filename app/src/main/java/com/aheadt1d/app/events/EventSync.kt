package com.aheadt1d.app.events

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.aheadt1d.app.BuildConfig
import com.aheadt1d.app.auth.AuthPrefs
import com.aheadt1d.app.network.ServerConfig
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Two-way sync of logged events (notes/tags) with ahead-backend, so they
 * show up in the web portal and its doctor report, and anything logged or
 * edited on the web comes back to the phone. The decisions live in
 * [EventSyncPlanner]; this is only the I/O around it.
 *
 * Safety rules, because this sits next to the alert pipeline:
 * - It never runs inside an alert decision. [requestSync] launches on its
 *   own background scope and returns immediately; GlucoseCheckRunner calls it
 *   only after the check (and its notification refresh) is done.
 * - Every failure is swallowed and retried on the next cycle. A dead server
 *   just means events wait on the phone.
 * - Events written here from the server go straight to the DAO, never
 *   through EventLogDialogs, so a CORRECTION logged on the web does NOT open
 *   a correction-tracking window on the phone (that needs the in-app tap).
 * - No Room schema change: the localId -> server mapping is a side map in
 *   its own SharedPreferences file.
 */
object EventSync {
    private const val TAG = "EventSync"
    private const val PREFS = "ahead_event_sync"
    private const val KEY_INSTALL_ID = "install_id"
    private const val KEY_CURSOR = "cursor"
    private const val KEY_MAP = "synced_map"
    private const val KEY_ACCOUNT = "account"
    private const val KEY_LAST_RUN = "last_run_ms"
    private const val MIN_INTERVAL_MS = 4 * 60 * 1000L
    private const val BATCH = 200
    private const val MAX_ROUNDS = 20

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** Fire-and-forget. [force] skips the 4-minute throttle (used right
     *  after the user logs/edits/deletes something). */
    fun requestSync(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        scope.launch {
            runCatching { syncNow(app, force) }
                .onFailure { Log.w(TAG, "event sync failed, will retry next cycle", it) }
        }
    }

    private suspend fun syncNow(context: Context, force: Boolean) = mutex.withLock {
        val apiKey = AuthPrefs.deviceApiKey(context) ?: return@withLock
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(KEY_LAST_RUN, 0L) < MIN_INTERVAL_MS) return@withLock
        prefs.edit().putLong(KEY_LAST_RUN, now).apply()

        // Signed into a different account than last time: start over, so one
        // account's mapping/cursor never leaks into another's.
        val account = AuthPrefs.email(context).orEmpty()
        if (prefs.getString(KEY_ACCOUNT, null) != account) {
            prefs.edit().remove(KEY_MAP).remove(KEY_CURSOR).putString(KEY_ACCOUNT, account).apply()
        }

        val dao = AppDatabase.getInstance(context).userEventDao()
        val installId = installId(prefs)
        var synced = loadMap(prefs)
        var cursor = prefs.getLong(KEY_CURSOR, 0L)

        repeat(MAX_ROUNDS) {
            val local = dao.getAllOnce().map { it.toLocal() }
            val pushes = EventSyncPlanner.planPushes(local, synced, installId).take(BATCH)

            val body = JSONObject().apply {
                put("since", cursor)
                put("changes", JSONArray().apply {
                    val stamp = System.currentTimeMillis()
                    pushes.forEach { p ->
                        put(JSONObject().apply {
                            put("clientId", p.clientId)
                            put("time", if (p.deleted) stamp else p.timestamp)
                            put("tag", p.tag)
                            p.note?.let { put("note", it) }
                            p.glucoseAtTime?.let { put("glucoseAtTime", it.toDouble()) }
                            put("updatedAt", stamp)
                            put("deleted", p.deleted)
                        })
                    }
                })
            }
            val response = post(context, apiKey, body)

            // Pushes the server accepted are now in sync (rejected ones are
            // malformed and would be rejected forever, so mark them too
            // rather than retrying every cycle).
            val next = synced.toMutableMap()
            pushes.forEach { p ->
                if (p.deleted) next.remove(p.localId)
                else next[p.localId] = EventSyncPlanner.SyncedState(p.clientId, p.fingerprint)
            }

            val remote = parseRemote(response.optJSONArray("events"))
            val freshLocal = dao.getAllOnce().map { it.toLocal() }
            for (action in EventSyncPlanner.planApply(remote, freshLocal, next)) {
                when (action) {
                    is EventSyncPlanner.Apply.Insert -> {
                        val r = action.remote
                        val id = dao.insert(UserEvent(timestamp = r.timestamp, tag = r.tag, note = r.note, glucoseAtTime = r.glucoseAtTime))
                        next[id] = EventSyncPlanner.SyncedState(r.clientId, EventSyncPlanner.fingerprint(r))
                    }
                    is EventSyncPlanner.Apply.Update -> {
                        val r = action.remote
                        dao.getById(action.localId)?.let {
                            dao.update(it.copy(timestamp = r.timestamp, tag = r.tag, note = r.note, glucoseAtTime = r.glucoseAtTime))
                        }
                        next[action.localId] = EventSyncPlanner.SyncedState(r.clientId, EventSyncPlanner.fingerprint(r))
                    }
                    is EventSyncPlanner.Apply.Delete -> {
                        dao.getById(action.localId)?.let { dao.delete(it) }
                        next.remove(action.localId)
                    }
                }
            }

            synced = next
            cursor = response.optLong("cursor", cursor)
            saveMap(prefs, synced)
            prefs.edit().putLong(KEY_CURSOR, cursor).apply()

            val morePushes = EventSyncPlanner.planPushes(dao.getAllOnce().map { it.toLocal() }, synced, installId).isNotEmpty()
            if (!response.optBoolean("hasMore", false) && !morePushes) return@withLock
        }
    }

    private fun post(context: Context, apiKey: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url("${ServerConfig.getBaseUrl(context)}/api/events/sync")
            .addHeader("X-Ahead-Api-Key", apiKey)
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string()
            if (!response.isSuccessful || text == null) throw IOException("events/sync failed: ${response.code}")
            if (BuildConfig.DEBUG) Log.d(TAG, "events/sync ok")
            return JSONObject(text)
        }
    }

    private fun parseRemote(array: JSONArray?): List<EventSyncPlanner.Remote> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            EventSyncPlanner.Remote(
                clientId = o.getString("clientId"),
                timestamp = o.getLong("time"),
                tag = o.getString("tag"),
                note = if (o.isNull("note")) null else o.optString("note").takeIf { it.isNotBlank() },
                glucoseAtTime = if (o.isNull("glucoseAtTime")) null else o.optDouble("glucoseAtTime").toFloat(),
                deleted = o.optBoolean("deleted", false)
            )
        }
    }

    private fun UserEvent.toLocal() = EventSyncPlanner.Local(id, timestamp, tag, note, glucoseAtTime)

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun installId(prefs: SharedPreferences): String =
        prefs.getString(KEY_INSTALL_ID, null) ?: UUID.randomUUID().toString().replace("-", "").take(12).also {
            prefs.edit().putString(KEY_INSTALL_ID, it).apply()
        }

    private fun loadMap(prefs: SharedPreferences): Map<Long, EventSyncPlanner.SyncedState> {
        val raw = prefs.getString(KEY_MAP, null) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associate { k ->
                val v = o.getJSONObject(k)
                k.toLong() to EventSyncPlanner.SyncedState(v.getString("c"), v.getString("h"))
            }
        }.getOrDefault(emptyMap())
    }

    private fun saveMap(prefs: SharedPreferences, map: Map<Long, EventSyncPlanner.SyncedState>) {
        val o = JSONObject()
        map.forEach { (id, s) -> o.put(id.toString(), JSONObject().put("c", s.clientId).put("h", s.fingerprint)) }
        prefs.edit().putString(KEY_MAP, o.toString()).apply()
    }
}
