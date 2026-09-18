package com.aheadt1d.app.state

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Exposes the in-memory debug injection state to other Ahead apps (like Ahead Lite)
 * running on the same device, so companion apps can immediately show or hide
 * the injected test data warning banner without needing network polling.
 */
class DebugStateProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == "isTestActive") {
            return Bundle().apply {
                putBoolean("isActive", DebugGlucoseOverride.isActive)
                putString("disclaimer", DebugGlucoseOverride.DISCLAIMER)
                putString("disclaimerShort", DebugGlucoseOverride.DISCLAIMER_SHORT)
            }
        }
        return null
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.aheadt1d.app.debug.provider"
    }
}
