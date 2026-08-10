package com.aheadt1d.app.upload

import com.aheadt1d.app.health.GlucosePoint

/** One upload destination - NightscoutUploader and WebhookUploader are the
 *  only two implementations for now (Tidepool would be a real future third,
 *  deliberately skipped - its OAuth-style app registration is a much bigger
 *  lift than "paste in a URL and a secret"). Both [upload] and
 *  [testConnection] return a plain success/failure + human-readable detail
 *  rather than throwing, since UploadSettingsActivity shows that detail
 *  directly to a non-technical user - "why didn't this work" needs to be
 *  something other than a stack trace. */
interface Uploader {
    suspend fun upload(points: List<GlucosePoint>): UploadResult
    suspend fun testConnection(): UploadResult
}

sealed class UploadResult {
    data object Success : UploadResult()
    data class Failure(val detail: String) : UploadResult()
}
