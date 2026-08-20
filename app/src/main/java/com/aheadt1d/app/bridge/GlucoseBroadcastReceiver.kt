package com.aheadt1d.app.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aheadt1d.app.work.GlucoseCheckRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Real-time push from AheadBLE V3 (com.ahead.ble) - a separate GPLv3 app
 * reading the Dexcom G7 sensor directly over BLE, no shared code with this
 * app (see the workspace CLAUDE.md). Health Connect is the durable store
 * V3 also writes to; this is the instant, REDUNDANT path, specifically so a
 * Health Connect write glitch/failure doesn't silently cost a reading -
 * that redundancy was the owner's explicit ask (2026-08-20).
 *
 * Deliberately does NOT feed the extras straight into the alert pipeline.
 * ahead-android's own history (see GlucoseCheckRunner.readPoints's doc, and
 * the removed NightscoutFallbackClient) is that a second automatic data
 * source silently substituted into this pipeline has caused two real
 * incidents - broken rate math from a duplicate writer, and a stale reading
 * scored as current. So instead: (1) buffer the reading, (2) immediately
 * trigger a real GlucoseCheckRunner cycle, which re-reads Health Connect as
 * always. If Health Connect's own read already covers this timestamp (the
 * common case - V3 also just wrote it there), the buffered value is
 * silently discarded unused (see BroadcastGlucoseBuffer.consumeIfNewerThan)
 * and nothing about this reading ever differs from a normal HC-sourced one.
 * ONLY if Health Connect's read is still missing it does GlucoseCheckRunner
 * fold the buffered value in - as an explicitly-marked, unconfirmed point
 * (RawReading.wasBroadcastSupplemented), not silently indistinguishable
 * from a verified read.
 *
 * Trust: this receiver is permission-gated at signature level (see the
 * manifest's <permission>/<receiver> declarations) so only an app signed
 * with this app's own certificate can deliver it - not a random app on the
 * device. On top of that, sanity-checks the payload itself (plausible mg/dL
 * range, plausible recency) before trusting it at all, and never buffers a
 * backfill (isBackfill=true) reading - only a live reading is urgent enough
 * to justify bypassing the normal 5-min cadence.
 */
class GlucoseBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_GLUCOSE_UPDATE) return

        val source = intent.getStringExtra(EXTRA_SOURCE)
        if (source != EXPECTED_SOURCE) {
            Log.w(TAG, "Ignoring broadcast with unexpected source='$source'")
            return
        }

        if (!intent.hasExtra(EXTRA_MG_DL) || !intent.hasExtra(EXTRA_TIMESTAMP_MILLIS)) {
            Log.w(TAG, "Ignoring malformed broadcast - missing required extras")
            return
        }
        val mgDl = intent.getIntExtra(EXTRA_MG_DL, -1)
        val timestampMillis = intent.getLongExtra(EXTRA_TIMESTAMP_MILLIS, -1L)
        val trendRate = if (intent.hasExtra(EXTRA_TREND_RATE)) intent.getDoubleExtra(EXTRA_TREND_RATE, 0.0) else null
        val isBackfill = intent.getBooleanExtra(EXTRA_IS_BACKFILL, false)

        if (mgDl !in PLAUSIBLE_MG_DL_RANGE) {
            Log.w(TAG, "Ignoring implausible mgDl=$mgDl")
            return
        }
        val now = System.currentTimeMillis()
        val age = now - timestampMillis
        if (age > MAX_STALENESS_MS || age < -MAX_CLOCK_SKEW_MS) {
            Log.w(TAG, "Ignoring reading with implausible timestamp (age=${age}ms, mgDl=$mgDl)")
            return
        }
        if (isBackfill) {
            // Historical catch-up data, not urgent "right now" information -
            // no reason to bypass the normal cadence or feed the fallback path.
            Log.d(TAG, "Backfill reading received (mgDl=$mgDl) - not urgent, ignoring for the live/fallback path")
            return
        }

        Log.d(TAG, "Live reading from AheadBLE V3: mgDl=$mgDl timestampMillis=$timestampMillis trendRate=$trendRate")
        BroadcastGlucoseBuffer.offer(
            BroadcastGlucoseBuffer.PendingReading(
                mgDl = mgDl,
                timestampMillis = timestampMillis,
                trendRate = trendRate,
                receivedAtMillis = now,
            )
        )

        // GlucoseCheckRunner.run() does real I/O (Health Connect + a backend
        // POST) - onReceive() must return quickly, so this needs goAsync() to
        // keep the process alive long enough for that coroutine to finish
        // rather than being killed the moment onReceive returns.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                GlucoseCheckRunner.run(context.applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "Triggered check cycle failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "GlucoseBroadcastRcvr"

        const val ACTION_GLUCOSE_UPDATE = "com.ahead.ble.action.GLUCOSE_UPDATE"
        private const val EXTRA_MG_DL = "mgDl"
        private const val EXTRA_TIMESTAMP_MILLIS = "timestampMillis"
        private const val EXTRA_TREND_RATE = "trendRate"
        private const val EXTRA_IS_BACKFILL = "isBackfill"
        private const val EXTRA_SOURCE = "source"
        private const val EXPECTED_SOURCE = "com.ahead.ble"

        private val PLAUSIBLE_MG_DL_RANGE = 20..600
        // A live reading arriving noticeably older than one G7 sampling
        // interval (5 min) is suspect - real transmission/processing delay is
        // seconds, not minutes. Generous enough to tolerate real-world jitter
        // without being so wide it accepts obviously-wrong data.
        private const val MAX_STALENESS_MS = 15 * 60 * 1000L
        private const val MAX_CLOCK_SKEW_MS = 2 * 60 * 1000L
    }
}
