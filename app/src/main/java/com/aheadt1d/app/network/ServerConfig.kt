package com.aheadt1d.app.network

import android.content.Context
import com.aheadt1d.app.BuildConfig

/**
 * Manages the backend server endpoint configuration.
 *
 * Defaults to [BuildConfig.BACKEND_BASE_URL] (e.g. Railway or production server).
 * If a custom URL is configured (e.g. a local Windows PC server running via
 * Cloudflare Tunnel or local LAN Wi-Fi for $0/mo self-hosting), that URL
 * takes precedence.
 */
object ServerConfig {
    private const val PREFS_NAME = "ahead_network_prefs"
    const val KEY_CUSTOM_BACKEND_URL = "custom_backend_url"

    fun getBaseUrl(context: Context? = null): String {
        if (context != null) {
            val custom = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_CUSTOM_BACKEND_URL, null)?.trim()
            if (!custom.isNullOrEmpty()) {
                return custom.removeSuffix("/")
            }
        }
        return BuildConfig.BACKEND_BASE_URL
    }

    fun clearStaleUrl(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(KEY_CUSTOM_BACKEND_URL).apply()
    }

    fun setCustomBaseUrl(context: Context, url: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (url.isNullOrBlank()) {
            prefs.edit().remove(KEY_CUSTOM_BACKEND_URL).apply()
        } else {
            val clean = url.trim().removeSuffix("/")
            prefs.edit().putString(KEY_CUSTOM_BACKEND_URL, clean).apply()
        }
    }

    fun isCustomUrl(context: Context): Boolean {
        return !context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_BACKEND_URL, null).isNullOrBlank()
    }
}
