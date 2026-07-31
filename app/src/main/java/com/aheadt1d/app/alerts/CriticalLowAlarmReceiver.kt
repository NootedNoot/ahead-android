package com.aheadt1d.app.alerts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires every ~25s while CriticalLowSiren is active (see its
 * setAlarmClock() chain). goAsync() isn't needed here - unlike
 * EmergencyAlertReceiver's SMS send, CriticalLowSiren.tick() does no
 * suspending work, just synchronous system-service calls.
 */
class CriticalLowAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TICK) return
        CriticalLowSiren.tick(context)
    }

    companion object {
        const val ACTION_TICK = "com.aheadt1d.app.action.CRITICAL_LOW_SIREN_TICK"
    }
}
