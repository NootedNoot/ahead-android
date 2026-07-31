package com.aheadt1d.app.alerts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.content.edit

/**
 * Owns the two alerting channels, kept separate from the silent ongoing
 * status channel in GlucoseNotifier:
 *
 *  - Yellow: high-importance. Never escalates - no DND bypass, no
 *    full-screen intent, nothing lock-screen-public. That's by construction:
 *    the escalation features only exist on the red path.
 *  - Red: high-importance, setBypassDnd(true) so the notification itself
 *    (heads-up, vibration) pierces Do Not Disturb.
 *
 * Both channels are deliberately silent (setSound(null, null)) - actual
 * alert sound plays via AlertTones' direct MediaPlayer playback instead (see
 * its class doc), not the channel's own sound attribute. Playing both would
 * double up.
 *
 * BOTH channel ids are versioned (glucose_alerts_active_vN /
 * glucose_alerts_yellow_vN), because Android channels are immutable to the
 * app once created AND deleting + recreating the SAME id resurrects the old
 * settings instead of applying the new ones (anti-abuse - learned the hard
 * way migrating yellow off its old default sound: a straight delete+recreate
 * with the same literal id silently kept the old sound). The only way to
 * actually change a channel's settings post-creation is a fresh id: create
 * the new one first (never a moment with no channel), delete the old one,
 * persist the new id.
 *
 * Red's migration additionally triggers on a bypassDnd mismatch: the flag
 * only sticks if the app holds Notification Policy Access at the moment the
 * channel is CREATED, so when policy access is granted after the channel
 * already exists without bypass, this is what fixes it. If the user later
 * revokes policy access, the existing channel keeps whatever bypass flag it
 * has; there is no downward migration.
 *
 * Separately, [dndAccessRegressed] tracks whether policy access was ever
 * observed granted and has since gone missing - see its doc for why a
 * REGRESSION (as opposed to never having granted it) is what's worth
 * surfacing to the user.
 */
object AlertChannels {
    private const val PREFS_NAME = "ahead_alert_channels"
    private const val KEY_RED_CHANNEL_ID = "red_channel_id"
    private const val KEY_YELLOW_CHANNEL_ID = "yellow_channel_id"
    private const val KEY_DND_EVER_GRANTED = "dnd_ever_granted"
    private const val KEY_SOUND_SCHEME_VERSION = "sound_scheme_version"
    private const val DEFAULT_RED_CHANNEL_ID = "glucose_alerts_active"
    private const val DEFAULT_YELLOW_CHANNEL_ID = "glucose_alerts_yellow"
    private const val TAG = "AlertChannels"

    // 2026-07-31: bumped when alert sound moved from each channel's own
    // sound attribute to AlertTones' direct playback. A channel created
    // under the OLD scheme still has a baked-in sound that would now play
    // ALONGSIDE the new direct tone, doubling up - any install below this
    // version gets both channels migrated to a fresh id once, silent.
    // v2->v3: the first pass at this migration deleted+recreated yellow
    // using the SAME literal id, which - like red's already-documented
    // anti-abuse quirk - resurrected the old sound instead of clearing it,
    // while still marking the migration complete. v3 uses a versioned id
    // for yellow too (see ensure()) and re-runs for anyone who landed on v2.
    private const val SOUND_SCHEME_VERSION = 3

    fun currentRedChannelId(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RED_CHANNEL_ID, DEFAULT_RED_CHANNEL_ID) ?: DEFAULT_RED_CHANNEL_ID

    fun currentYellowChannelId(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_YELLOW_CHANNEL_ID, DEFAULT_YELLOW_CHANNEL_ID) ?: DEFAULT_YELLOW_CHANNEL_ID

    /** Idempotent and cheap - safe to call from Application.onCreate, before
     *  every alert post, and after returning from the DND-access settings
     *  screen (that last one is what actually triggers the DND migration). */
    fun ensure(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Latches "this device has had DND access granted at least once" -
        // read by dndAccessRegressed below. ensure() runs often enough (app
        // start, every alert, return-from-settings) that this stays current
        // without any dedicated polling.
        if (nm.isNotificationPolicyAccessGranted) {
            prefs.edit { putBoolean(KEY_DND_EVER_GRANTED, true) }
        }

        val needsSoundMigration = prefs.getInt(KEY_SOUND_SCHEME_VERSION, 1) < SOUND_SCHEME_VERSION

        val yellowId = currentYellowChannelId(context)
        if (nm.getNotificationChannel(yellowId) == null) {
            nm.createNotificationChannel(buildYellowChannel(yellowId))
        } else {
            val yellowChannel = nm.getNotificationChannel(yellowId)
            if (needsSoundMigration && yellowChannel?.sound != null) {
                val newId = nextVersionedId(yellowId, DEFAULT_YELLOW_CHANNEL_ID)
                nm.createNotificationChannel(buildYellowChannel(newId))
                nm.deleteNotificationChannel(yellowId)
                prefs.edit { putString(KEY_YELLOW_CHANNEL_ID, newId) }
            }
        }

        val redId = currentRedChannelId(context)
        if (nm.getNotificationChannel(redId) == null) {
            nm.createNotificationChannel(buildRedChannel(redId))
        }

        val redChannel = nm.getNotificationChannel(redId) ?: return
        val redNeedsDndMigration = !redChannel.canBypassDnd() && nm.isNotificationPolicyAccessGranted
        val redNeedsSoundMigration = needsSoundMigration && redChannel.sound != null
        if (redNeedsDndMigration || redNeedsSoundMigration) {
            val newId = nextVersionedId(redId, DEFAULT_RED_CHANNEL_ID)
            nm.createNotificationChannel(buildRedChannel(newId))
            nm.deleteNotificationChannel(redId)
            prefs.edit { putString(KEY_RED_CHANNEL_ID, newId) }
            if (nm.getNotificationChannel(newId)?.canBypassDnd() != true) {
                Log.w(TAG, "Red channel $newId still can't bypass DND despite policy access")
            }
        }

        if (needsSoundMigration) {
            prefs.edit { putInt(KEY_SOUND_SCHEME_VERSION, SOUND_SCHEME_VERSION) }
        }
    }

    private fun buildYellowChannel(id: String): NotificationChannel =
        NotificationChannel(id, "Glucose warnings", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Early warnings when glucose is trending out of range"
            enableVibration(true)
            setSound(null, null)
        }

    private fun buildRedChannel(id: String): NotificationChannel =
        NotificationChannel(id, "Glucose red alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Urgent alerts when glucose is dangerously low or high"
            setBypassDnd(true)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 600)
            setSound(null, null)
        }

    private fun nextVersionedId(currentId: String, baseId: String): String {
        val version = Regex("_v(\\d+)$").find(currentId)?.groupValues?.get(1)?.toIntOrNull()
        return "${baseId}_v${(version ?: 1) + 1}"
    }

    /**
     * True when DND access was observed granted at some point (per the latch
     * in [ensure]) but is NOT granted right now - i.e. it REGRESSED, as
     * distinct from never having been granted. A user who consciously skipped
     * the setup wizard's DND step made that choice already; nagging them on
     * every home-screen visit would just be alarm fatigue for a non-change.
     * A user who granted it and then had it silently revoked (a "clean up
     * permissions" system prompt, an OEM battery/permission auto-revoke,
     * manually toggling it off) is the case actually worth surfacing, since
     * it means a red alert could arrive muted with no warning.
     */
    fun dndAccessRegressed(context: Context): Boolean {
        val everGranted = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DND_EVER_GRANTED, false)
        if (!everGranted) return false
        val nm = context.getSystemService(NotificationManager::class.java)
        return !nm.isNotificationPolicyAccessGranted
    }
}
