package com.aheadt1d.app.upload

import android.content.Context
import android.util.Log
import com.aheadt1d.app.BuildConfig
import com.aheadt1d.app.health.GlucosePoint

/**
 * Called from GlucoseCheckRunner on every check cycle, right alongside the
 * Health Connect read that already drives everything else - piggybacking on
 * that existing cadence rather than a separate timer, so "upload" means
 * exactly "whatever Ahead itself just saw," never a second, independently-
 * paced read of its own.
 *
 * Deliberately best-effort and fully isolated from the rest of the pipeline:
 * a failed or slow upload (bad credentials, an unreachable Nightscout site,
 * a flaky webhook) must never affect glucose reading, alerting, or the
 * chart - this is a downstream convenience feature, not part of the safety
 * path. Every exception is caught here; nothing propagates to the caller.
 */
object UploadCoordinator {
    private const val TAG = "UploadCoordinator"

    suspend fun maybeUpload(context: Context, points: List<GlucosePoint>) {
        try {
            val method = UploadPrefs.method(context)
            if (method == UploadMethod.NONE || points.isEmpty()) return

            val lastUploaded = UploadPrefs.lastUploadedEpochMs(context)
            val newPoints = points.filter { it.time.toEpochMilli() > lastUploaded }
            if (newPoints.isEmpty()) return

            val uploader = uploaderFor(context, method) ?: return
            when (val result = uploader.upload(newPoints)) {
                is UploadResult.Success -> {
                    UploadPrefs.setLastUploadedEpochMs(context, newPoints.last().time.toEpochMilli())
                    UploadPrefs.recordUploadResult(context, success = true, detail = null)
                    if (BuildConfig.DEBUG) Log.d(TAG, "uploaded ${newPoints.size} point(s) via $method")
                }
                is UploadResult.Failure -> {
                    UploadPrefs.recordUploadResult(context, success = false, detail = result.detail)
                    Log.w(TAG, "upload via $method failed: ${result.detail}")
                }
            }
        } catch (e: Exception) {
            // Catch-all is deliberate (see class doc) - an uploader
            // implementation bug must never take down the real glucose
            // pipeline that calls this.
            Log.w(TAG, "unexpected error during upload - ignoring, will retry next cycle", e)
        }
    }

    private fun uploaderFor(context: Context, method: UploadMethod): Uploader? = when (method) {
        UploadMethod.NIGHTSCOUT -> NightscoutUploader.from(
            baseUrl = UploadPrefs.nightscoutUrl(context),
            token = UploadPrefs.nightscoutToken(context),
            secret = UploadPrefs.nightscoutSecret(context),
            deviceName = UploadPrefs.nightscoutDeviceName(context),
        )
        UploadMethod.WEBHOOK -> WebhookUploader.from(
            url = UploadPrefs.webhookUrl(context),
            headerName = UploadPrefs.webhookHeaderName(context),
            headerValue = UploadPrefs.webhookHeaderValue(context),
        )
        UploadMethod.NONE -> null
    }
}
