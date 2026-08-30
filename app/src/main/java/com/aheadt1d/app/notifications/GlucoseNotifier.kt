package com.aheadt1d.app.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.aheadt1d.app.MainActivity
import com.aheadt1d.app.R
import com.aheadt1d.app.alerts.AlertExplainer
import com.aheadt1d.app.state.staleGuidance
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

/**
 * Builds the single persistent glucose-status notification. GlucoseStatusService
 * owns *when* this gets called (new readings, staleness ticks); this object
 * only owns *what* it looks like.
 */
object GlucoseNotifier {
    const val CHANNEL_ID = "glucose_status"

    private val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())

    /** IMPORTANCE_LOW guarantees no sound/heads-up on its own, but the sound
     *  and vibration are disabled explicitly too so this can never surprise
     *  anyone even if a future change bumps the importance. */
    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Glucose status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Ongoing glucose reading and trend, updated automatically"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun buildNotification(context: Context, state: GlucoseDisplayState): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val (icon, title, text) = when (state) {
            // Title: "142 mg/dL ↓ (-4 mg/dL)" - the parenthesized value is the
            // raw delta since the previous reading, deliberately labeled with
            // units to distinguish it from the per-minute rate on line two.
            is GlucoseDisplayState.Reading -> Triple(
                NotificationIconFactory.readingIcon(context, state.value, state.arrow),
                "${severityPrefix(state.severity)}${state.value} mg/dL ${state.arrow.label}${deltaParen(state.deltaFromPrevious)}",
                // Alert Transparency: during yellow/red, the collapsed line
                // becomes the plain-language "why" (AlertExplainer) instead
                // of the raw rate/projection numbers - those move to the
                // expanded view below, which already exists as this
                // notification's own "why am I seeing this" affordance
                // (tap/swipe to expand). Severity "none" is unchanged - no
                // alert to explain, so the technical line stays as-is.
                if (state.severity == "red" || state.severity == "yellow") {
                    "${AlertExplainer.oneLiner(state.value, state.ratePerMinute, state.projected, state.projectedExtended)} · as of ${timeFormatter.format(state.readingTime)}"
                } else {
                    "${rateText(state.ratePerMinute)}${projectionText(state.projected, state.projectedExtended)} · as of ${timeFormatter.format(state.readingTime)}"
                }
            )
            is GlucoseDisplayState.Stale -> Triple(
                NotificationIconFactory.warningIcon(context),
                "⚠️ No new data — ${formatAge(state.ageMinutes)} ago",
                // Guidance is cause-aware (shared staleGuidance): an app-side
                // blockage (revoked permission, HC missing) names itself
                // instead of misdirecting the user to their sensor.
                "Last reading ${state.lastValue} mg/dL ${state.lastArrow.label}. ${staleGuidance(state.blockedReason)}"
            )
            GlucoseDisplayState.NoData -> Triple(
                NotificationIconFactory.noDataIcon(context),
                "No recent data",
                "Waiting for a glucose reading"
            )
        }

        // A red reading shows its full value on the lock screen; everything
        // else stays redacted there. The interrupting alert (AlertNotifier) is
        // separate - this only governs how the ongoing status line appears.
        val visibility = if ((state as? GlucoseDisplayState.Reading)?.severity == "red") {
            Notification.VISIBILITY_PUBLIC
        } else {
            Notification.VISIBILITY_PRIVATE
        }

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(visibility)
            .setCustomBigContentView(buildExpandedView(context, state))
            .setStyle(Notification.DecoratedCustomViewStyle())

        // Accent color tracks the same severity tier the title's 🔴/⚠️ prefix
        // already shows (LatestTrend.severity, i.e. trend-detector.js's
        // classification, tolerance-gated in GlucoseStatusService) - so the
        // shade's icon tint reads at a glance: green in range, amber caution,
        // red urgent. Same accent colors the interrupting alerts already use
        // (AlertNotifier sets R.color.low/high), so the two notification
        // layers can never disagree about what a tier looks like. Display
        // only - never carries meaning alone (prefix + text remain the
        // accessible channel), and Stale/NoData stay neutral: an accent there
        // would falsely suggest a known-current severity.
        severityAccentRes(state)?.let { builder.setColor(ContextCompat.getColor(context, it)) }

        return builder.build()
    }

    /** Accent color resource for the ongoing notification, or null for the
     *  states (stale / no data) that should stay neutral. */
    private fun severityAccentRes(state: GlucoseDisplayState): Int? {
        val reading = state as? GlucoseDisplayState.Reading ?: return null
        return when (reading.severity) {
            "red" -> R.color.low
            "yellow" -> R.color.high
            else -> R.color.glucose_normal
        }
    }

    private fun buildExpandedView(context: Context, state: GlucoseDisplayState): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.notification_glucose_expanded)
        when (state) {
            is GlucoseDisplayState.Reading -> {
                views.setImageViewIcon(
                    R.id.iv_arrow,
                    NotificationIconFactory.arrowIcon(context, state.arrow, NotificationIconFactory.EXPANDED_ICON_SIZE_PX)
                )
                views.setTextViewText(
                    R.id.tv_value,
                    "${severityPrefix(state.severity)}${state.value} mg/dL ${state.arrow.label}${deltaParen(state.deltaFromPrevious)}"
                )
                views.setTextViewText(R.id.tv_time, "As of ${timeFormatter.format(state.readingTime)}")
                views.setTextViewText(
                    R.id.tv_delta,
                    "${rateText(state.ratePerMinute)}${projectionText(state.projected, state.projectedExtended)}"
                )
            }
            is GlucoseDisplayState.Stale -> {
                views.setImageViewIcon(
                    R.id.iv_arrow,
                    NotificationIconFactory.warningIcon(context, NotificationIconFactory.EXPANDED_ICON_SIZE_PX)
                )
                views.setTextViewText(R.id.tv_value, "⚠️ No new data — ${formatAge(state.ageMinutes)} ago")
                views.setTextViewText(
                    R.id.tv_time,
                    "Last: ${state.lastValue} mg/dL ${state.lastArrow.label} at ${timeFormatter.format(state.lastReadingTime)}"
                )
                views.setTextViewText(R.id.tv_delta, staleGuidance(state.blockedReason))
            }
            GlucoseDisplayState.NoData -> {
                views.setImageViewIcon(
                    R.id.iv_arrow,
                    NotificationIconFactory.noDataIcon(context, NotificationIconFactory.EXPANDED_ICON_SIZE_PX)
                )
                views.setTextViewText(R.id.tv_value, "No recent data")
                views.setTextViewText(R.id.tv_time, "")
                views.setTextViewText(R.id.tv_delta, "Waiting for a glucose reading")
            }
        }
        return views
    }

    /** Raw mg/dL change since the previous reading, e.g. " (-4 mg/dL)".
     *  Empty on the first reading (no previous point to diff against). */
    private fun deltaParen(delta: Int?): String = when {
        delta == null -> ""
        delta > 0 -> " (+$delta mg/dL)"
        delta < 0 -> " (-${abs(delta)} mg/dL)"
        else -> " (±0 mg/dL)"
    }

    /** Explicitly labeled per-minute rate, e.g. "Rate: -2.0/min", so it can't
     *  be mistaken for the raw delta shown in the title. */
    private fun rateText(rate: Double?): String = when {
        rate == null -> "Rate: —"
        rate > 0 -> "Rate: +${"%.1f".format(rate)}/min"
        else -> "Rate: ${"%.1f".format(rate)}/min"
    }

    /** 🔴 matches the backend's own red-alert push convention
     *  (buildNotificationMessage in trend-detector.js); ⚠️ marks yellow. */
    private fun severityPrefix(severity: String?): String = when (severity) {
        "red" -> "🔴 "
        "yellow" -> "⚠️ "
        else -> ""
    }

    // Both windows explicitly - the alert tier can be decided off the 15-min or
    // the 30-min projection, so the text must never imply just one.
    private fun projectionText(projected: Int?, projectedExtended: Int?): String = when {
        projected != null && projectedExtended != null ->
            " · Projected: $projected in 15m · $projectedExtended in 30m"
        projected != null -> " · Projected: $projected in 15m"
        else -> ""
    }

    private fun formatAge(ageMinutes: Long): String = if (ageMinutes == 1L) "1 min" else "$ageMinutes min"
}
