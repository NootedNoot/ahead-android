package com.aheadt1d.app.alerts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.util.Log
import androidx.core.content.edit

/**
 * Owns the two alerting channels, kept separate from the silent ongoing
 * status channel in GlucoseNotifier:
 *
 *  - Yellow: high-importance, default notification sound. Never escalates -
 *    no DND bypass, no full-screen intent, nothing lock-screen-public. That's
 *    by construction: the escalation features only exist on the red path.
 *  - Red: high-importance, ALARM-stream sound, setBypassDnd(true) so red
 *    alerts pierce Do Not Disturb.
 *
 * The red channel id is versioned because of how bypassDnd actually works:
 * the flag only sticks if the app holds Notification Policy Access at the
 * moment the channel is CREATED, channels are immutable to the app after
 * creation, and deleting + recreating the same id resurrects the old
 * settings (anti-abuse). So when policy access is granted after the channel
 * already exists without bypass, the only fix is migrating to a fresh id:
 * create "glucose_alerts_active_v2" first (never a moment with no red
 * channel), delete the old one, persist the new id. Migration happens only
 * on that bypass mismatch - user tweaks to sound/importance are respected.
 *
 * If the user later revokes policy access, the existing channel keeps
 * whatever bypass flag it has; there is no downward migration.
 */
object AlertChannels {
    const val YELLOW_CHANNEL_ID = "glucose_alerts_yellow"

    private const val PREFS_NAME = "ahead_alert_channels"
    private const val KEY_RED_CHANNEL_ID = "red_channel_id"
    private const val DEFAULT_RED_CHANNEL_ID = "glucose_alerts_active"
    private const val TAG = "AlertChannels"

    fun currentRedChannelId(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RED_CHANNEL_ID, DEFAULT_RED_CHANNEL_ID) ?: DEFAULT_RED_CHANNEL_ID

    /** Idempotent and cheap - safe to call from Application.onCreate, before
     *  every alert post, and after returning from the DND-access settings
     *  screen (that last one is what actually triggers the migration). */
    fun ensure(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)

        if (nm.getNotificationChannel(YELLOW_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    YELLOW_CHANNEL_ID,
                    "Glucose warnings",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Early warnings when glucose is trending out of range"
                    enableVibration(true)
                }
            )
        }

        val redId = currentRedChannelId(context)
        if (nm.getNotificationChannel(redId) == null) {
            nm.createNotificationChannel(buildRedChannel(redId))
        }

        val redChannel = nm.getNotificationChannel(redId) ?: return
        if (!redChannel.canBypassDnd() && nm.isNotificationPolicyAccessGranted) {
            val newId = nextVersionedId(redId)
            nm.createNotificationChannel(buildRedChannel(newId))
            nm.deleteNotificationChannel(redId)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putString(KEY_RED_CHANNEL_ID, newId)
            }
            if (nm.getNotificationChannel(newId)?.canBypassDnd() != true) {
                Log.w(TAG, "Red channel $newId still can't bypass DND despite policy access")
            }
        }
    }

    private fun buildRedChannel(id: String): NotificationChannel =
        NotificationChannel(id, "Glucose red alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Urgent alerts when glucose is dangerously low or high"
            setBypassDnd(true)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 600)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }

    private fun nextVersionedId(currentId: String): String {
        val version = Regex("_v(\\d+)$").find(currentId)?.groupValues?.get(1)?.toIntOrNull()
        return "${DEFAULT_RED_CHANNEL_ID}_v${(version ?: 1) + 1}"
    }
}
