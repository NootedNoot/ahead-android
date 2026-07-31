package com.aheadt1d.app.alerts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires when the critical-low notification is swiped away from the shade
 * (Notification.Builder.setDeleteIntent) - treated exactly like tapping "I'm
 * treating this" on the takeover screen: stops the whole siren loop.
 * Without this, swiping the notification away would just clear it from the
 * shade while sound/vibration/voice kept repeating every ~25s with nothing
 * visible left to explain why - a swipe has to mean "I've got this", same as
 * the in-app button, not just "get this out of my notifications".
 */
class CriticalLowDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DISMISSED) return
        CriticalLowSiren.stop(context)
    }

    companion object {
        const val ACTION_DISMISSED = "com.aheadt1d.app.action.CRITICAL_LOW_NOTIFICATION_DISMISSED"
    }
}
