package com.aheadt1d.app.alarm

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aheadt1d.app.R
import com.aheadt1d.app.alerts.AlertChannels
import java.util.Locale

/**
 * Foreground Service that plays the loud wake-up alarm sound, triggers pulsing vibration,
 * and speaks the custom user message via TTS until dismissed or snoozed.
 * Configured specifically to pierce Do Not Disturb and silent profiles using USAGE_ALARM.
 */
class SafetyAlarmRingingService : Service(), TextToSpeech.OnInitListener {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private val handler = Handler(Looper.getMainLooper())
    private var customMessage: String = ""
    private var presetLabel: String = ""

    private val speechRunnable = object : Runnable {
        override fun run() {
            speakAlarmMessage()
            // Repeat voice announcement every 15 seconds while alarm is active
            handler.postDelayed(this, 15_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SafetyAlarmRingingService onCreate")

        // 1. Acquire WakeLock so CPU doesn't sleep while alarm is ringing
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Ahead:SafetyAlarmWakeLock")?.apply {
            setReferenceCounted(false)
            acquire(10 * 60_000L) // Safety cap at 10 minutes
        }

        // 2. Init Vibrator
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        // 3. Init TTS for custom message speaking
        try {
            tts = TextToSpeech(applicationContext, this)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create TextToSpeech: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        customMessage = intent?.getStringExtra(SafetyAlarmReceiver.EXTRA_MESSAGE)
            ?: SafetyAlarmPrefs.getCustomMessage(this)
        presetLabel = intent?.getStringExtra(SafetyAlarmReceiver.EXTRA_LABEL)
            ?: SafetyAlarmPrefs.getPresetLabel(this)

        Log.d(TAG, "onStartCommand label=$presetLabel msg=$customMessage")

        // Post foreground notification immediately
        val notification = buildAlarmNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Start loud alarm tone
        startAlarmAudio()

        // Start heavy vibration
        startVibration()

        // Schedule repeating spoken message
        handler.removeCallbacks(speechRunnable)
        handler.postDelayed(speechRunnable, 2500L) // slight initial delay so chime is heard first

        return START_STICKY
    }

    private fun buildAlarmNotification(): Notification {
        AlertChannels.ensureSafetyAlarmChannel(this)

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        // Full-screen / tap Intent opens SafetyAlarmActivity
        val fullScreenIntent = Intent(this, SafetyAlarmActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(SafetyAlarmReceiver.EXTRA_MESSAGE, customMessage)
            putExtra(SafetyAlarmReceiver.EXTRA_LABEL, presetLabel)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(this, 991, fullScreenIntent, flags)

        // Dismiss Action Intent
        val dismissIntent = Intent(this, SafetyAlarmReceiver::class.java).apply {
            action = SafetyAlarmReceiver.ACTION_DISMISS
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(this, 992, dismissIntent, flags)

        // Snooze Action Intent
        val snoozeIntent = Intent(this, SafetyAlarmReceiver::class.java).apply {
            action = SafetyAlarmReceiver.ACTION_SNOOZE
        }
        val snoozePendingIntent = PendingIntent.getBroadcast(this, 993, snoozeIntent, flags)

        val title = "🚨 AHEAD WAKE-UP ALARM: $presetLabel"
        val bodyText = if (customMessage.isNotBlank()) customMessage else "Time to wake up and check blood sugar."

        return NotificationCompat.Builder(this, AlertChannels.SAFETY_ALARM_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .addAction(R.drawable.ic_launcher_foreground, "DISMISS", dismissPendingIntent)
            .addAction(R.drawable.ic_launcher_foreground, "SNOOZE 10M", snoozePendingIntent)
            .build()
    }

    private fun startAlarmAudio() {
        if (mediaPlayer != null) return

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        try {
            var alarmUri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(audioAttrs)
                setDataSource(applicationContext, alarmUri ?: Uri.parse("android.resource://$packageName/${R.raw.tone_urgent_high}"))
                isLooping = true
                setVolume(1.0f, 1.0f)
                prepare()
                start()
            }
            Log.d(TAG, "Alarm MediaPlayer started with USAGE_ALARM")
        } catch (e: Exception) {
            Log.w(TAG, "Default alarm ringtone failed, trying raw asset: ${e.message}")
            try {
                mediaPlayer = MediaPlayer.create(this, R.raw.tone_urgent_high, audioAttrs, 0)?.apply {
                    isLooping = true
                    setVolume(1.0f, 1.0f)
                    start()
                }
            } catch (ex: Exception) {
                Log.e(TAG, "All alarm media playback failed: ${ex.message}")
            }
        }
    }

    private fun startVibration() {
        val pattern = longArrayOf(0, 800, 300, 800, 300, 1200, 400)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(pattern, 0) // 0 = repeat from start
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibrator failed: ${e.message}")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
            val audioAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            tts?.setAudioAttributes(audioAttrs)
            isTtsReady = true
            Log.d(TAG, "TTS initialized for SafetyAlarm")
        }
    }

    private fun speakAlarmMessage() {
        if (!isTtsReady || tts == null) return
        val speech = if (customMessage.isNotBlank()) {
            "Ahead Wake Up Alarm! $customMessage"
        } else {
            "Ahead Wake Up Alarm! Time to wake up and check your blood sugar."
        }

        try {
            val params = android.os.Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }
            tts?.speak(speech, TextToSpeech.QUEUE_FLUSH, params, "safety_alarm_${System.currentTimeMillis()}")
            Log.d(TAG, "Spoke alarm speech: $speech")
        } catch (e: Exception) {
            Log.w(TAG, "TTS speak failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "SafetyAlarmRingingService onDestroy")
        handler.removeCallbacks(speechRunnable)

        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {}

        try {
            vibrator?.cancel()
        } catch (e: Exception) {}

        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
        } catch (e: Exception) {}

        if (wakeLock?.isHeld == true) {
            try {
                wakeLock?.release()
            } catch (e: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "SafetyAlarmRinging"
        private const val NOTIFICATION_ID = 8820

        fun stopRinging(context: Context) {
            SafetyAlarmPrefs.setRinging(context, false)
            val intent = Intent(context, SafetyAlarmRingingService::class.java)
            context.stopService(intent)
        }
    }
}
