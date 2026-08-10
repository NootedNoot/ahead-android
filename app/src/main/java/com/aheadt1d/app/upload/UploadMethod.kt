package com.aheadt1d.app.upload

/** The destination GlucoseCheckRunner uploads readings to on every check
 *  cycle, alongside its normal Health Connect read - see UploadCoordinator. */
enum class UploadMethod {
    NONE, NIGHTSCOUT, WEBHOOK
}
