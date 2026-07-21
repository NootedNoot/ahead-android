package com.aheadt1d.app.network

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

/**
 * Stable per-install identifier sent with every backend request (X-Ahead-Device-Id)
 * so the otherwise-stateless backend can keep each device's trend state separate
 * instead of sharing two bare globals across every caller. Not a security
 * boundary - that's BuildConfig.AHEAD_API_KEY's job.
 */
object DeviceId {
    private const val PREFS_NAME = "ahead_device_id"
    private const val KEY_DEVICE_ID = "device_id"

    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit { putString(KEY_DEVICE_ID, id) }
        return id
    }
}
