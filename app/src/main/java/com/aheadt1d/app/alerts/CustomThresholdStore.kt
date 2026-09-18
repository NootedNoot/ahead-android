package com.aheadt1d.app.alerts

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * SharedPreferences-backed persistence for custom thresholds - same pattern
 * as LatestTrendStore's guess list (a small JSON array under one key), since
 * this is realistically a handful of entries, not a dataset that needs Room.
 *
 * Every read-modify-write mutator below is @Synchronized (locks on this
 * object itself, the standard Kotlin `object` pattern) - CustomThresholdCoordinator's
 * background evaluate() and CustomThresholdsActivity's UI-thread add/delete/
 * setEnabled/toggle actions can race on the SAME underlying JSON blob
 * (load-full-list -> mutate -> save-full-list), and without a shared lock a
 * UI edit landing mid-coordinator-cycle could silently be overwritten by the
 * coordinator's own stale-read save (a lost update). CustomThresholdCoordinator.evaluate()
 * additionally wraps its whole purge-decide-save cycle in
 * `synchronized(CustomThresholdStore) { ... }` using this same monitor, so
 * the two sides can never interleave.
 */
object CustomThresholdStore {
    private const val PREFS_NAME = "ahead_custom_thresholds"
    private const val KEY_THRESHOLDS = "thresholds"
    private const val TAG = "CustomThresholdStore"

    fun newId(): String = UUID.randomUUID().toString()

    @Synchronized
    fun load(context: Context): List<CustomThreshold> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_THRESHOLDS, null)
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(json)
            buildList {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { fromJson(it)?.let(::add) }
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun save(context: Context, thresholds: List<CustomThreshold>) {
        val array = JSONArray()
        thresholds.forEach { array.put(toJson(it)) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_THRESHOLDS, array.toString())
        }
    }

    @Synchronized
    fun add(context: Context, threshold: CustomThreshold) {
        save(context, load(context) + threshold)
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        save(context, load(context).filterNot { it.id == id })
    }

    @Synchronized
    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        save(context, load(context).map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    /** Drops any temporary threshold whose expiry has passed, so the list
     *  never quietly accumulates dead "watch for today" entries. Persistent
     *  thresholds ([CustomThreshold.expiresAtMs] null) are never touched. */
    @Synchronized
    fun purgeExpired(context: Context, nowMs: Long = System.currentTimeMillis()): List<CustomThreshold> {
        val current = load(context)
        val kept = current.filter { it.expiresAtMs == null || it.expiresAtMs > nowMs }
        if (kept.size != current.size) save(context, kept)
        return kept
    }

    /** Debug-only reset helper: clears the FIRED state (currentlyCrossed/
     *  lastFiredAtMs/lastFiredAtMetric) on every threshold without touching
     *  the user's actual configuration (kind/direction/amount/label/temporary/
     *  enabled) - mirrors why DebugMenuActivity's "Reset everything to normal"
     *  clears AlertCoordinator's own cooldown/latch prefs but never touches
     *  Voice Alert settings: leftover fired-state from a debug injection
     *  would otherwise quietly skew how the next real reading gets evaluated. */
    @Synchronized
    fun resetFiredState(context: Context) {
        save(context, load(context).map { it.copy(currentlyCrossed = false, lastFiredAtMs = null, lastFiredAtMetric = null) })
    }

    private fun toJson(t: CustomThreshold): JSONObject = JSONObject().apply {
        put("id", t.id)
        put("kind", t.kind.name)
        put("direction", t.direction.name)
        put("amount", t.amount)
        put("label", t.label)
        put("temporary", t.temporary)
        put("expiresAtMs", t.expiresAtMs ?: JSONObject.NULL)
        put("enabled", t.enabled)
        put("currentlyCrossed", t.currentlyCrossed)
        put("lastFiredAtMs", t.lastFiredAtMs ?: JSONObject.NULL)
        put("lastFiredAtMetric", t.lastFiredAtMetric ?: JSONObject.NULL)
    }

    // A malformed entry (future field removed, corrupt write) must never take
    // down the whole list - skip just that one entry rather than throwing.
    private fun fromJson(o: JSONObject): CustomThreshold? = runCatching {
        CustomThreshold(
            id = o.getString("id"),
            kind = CustomThreshold.Kind.valueOf(o.getString("kind")),
            direction = CustomThreshold.Direction.valueOf(o.getString("direction")),
            amount = o.getDouble("amount"),
            label = o.optString("label", ""),
            temporary = o.optBoolean("temporary", false),
            expiresAtMs = if (o.isNull("expiresAtMs")) null else o.optLong("expiresAtMs"),
            enabled = o.optBoolean("enabled", true),
            currentlyCrossed = o.optBoolean("currentlyCrossed", false),
            lastFiredAtMs = if (o.isNull("lastFiredAtMs")) null else o.optLong("lastFiredAtMs"),
            lastFiredAtMetric = if (o.isNull("lastFiredAtMetric")) null else o.optDouble("lastFiredAtMetric").takeUnless { it.isNaN() },
        )
    }.onFailure { Log.w(TAG, "Dropping malformed custom threshold entry: $o", it) }.getOrNull()
}
