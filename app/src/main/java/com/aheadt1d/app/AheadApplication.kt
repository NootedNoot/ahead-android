package com.aheadt1d.app

import android.app.Application
import com.aheadt1d.app.alerts.AlertChannels
import com.aheadt1d.app.events.AppDatabase
import com.aheadt1d.app.state.AppForegroundTracker
import com.aheadt1d.app.state.LatestTrendRepository
import com.aheadt1d.app.voice.VoiceAlertEngine

class AheadApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppForegroundTracker.register(this)
        LatestTrendRepository.init(this)
        // Opens (or creates) the events database eagerly so the first
        // quick-log tap isn't the one paying Room's cold-start cost.
        AppDatabase.getInstance(this)
        AlertChannels.ensure(this)
        // Warm the TTS engine so the first alert doesn't get skipped waiting on
        // async init. It stays silent unless an alert fires and voice is enabled.
        VoiceAlertEngine.init(this)
    }
}
