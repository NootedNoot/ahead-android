package com.aheadt1d.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.aheadt1d.app.R

/**
 * Voice Alerts settings. A master toggle plus one toggle per alert category.
 * When the master is off, the category toggles are disabled/greyed - they don't
 * apply because VoiceAlertEngine short-circuits on the master gate first.
 *
 * This screen only writes VoiceAlertPrefs; it never touches notification/DND/
 * escalation behavior. Voice and visual are independent.
 */
class VoiceAlertsActivity : AppCompatActivity() {

    private lateinit var masterSwitch: SwitchCompat
    private lateinit var redSwitch: SwitchCompat
    private lateinit var yellowSwitch: SwitchCompat
    private lateinit var signalLostSwitch: SwitchCompat
    private lateinit var plateauSwitch: SwitchCompat
    private lateinit var correctionSwitch: SwitchCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_alerts)

        findViewById<TextView>(R.id.backButton).setOnClickListener { finish() }

        masterSwitch = findViewById(R.id.masterSwitch)
        redSwitch = findViewById(R.id.redSwitch)
        yellowSwitch = findViewById(R.id.yellowSwitch)
        signalLostSwitch = findViewById(R.id.signalLostSwitch)
        plateauSwitch = findViewById(R.id.plateauSwitch)
        correctionSwitch = findViewById(R.id.correctionSwitch)

        masterSwitch.isChecked = VoiceAlertPrefs.isMasterEnabled(this)
        redSwitch.isChecked = VoiceAlertPrefs.isCategoryEnabled(this, VoiceAlertCategory.RED)
        yellowSwitch.isChecked = VoiceAlertPrefs.isCategoryEnabled(this, VoiceAlertCategory.YELLOW)
        signalLostSwitch.isChecked = VoiceAlertPrefs.isCategoryEnabled(this, VoiceAlertCategory.SIGNAL_LOST)
        plateauSwitch.isChecked = VoiceAlertPrefs.isCategoryEnabled(this, VoiceAlertCategory.PLATEAU)
        correctionSwitch.isChecked = VoiceAlertPrefs.isCategoryEnabled(this, VoiceAlertCategory.CORRECTION)

        masterSwitch.setOnCheckedChangeListener { _, checked ->
            VoiceAlertPrefs.setMasterEnabled(this, checked)
            applyMasterState(checked)
        }
        redSwitch.setOnCheckedChangeListener { _, checked ->
            VoiceAlertPrefs.setCategoryEnabled(this, VoiceAlertCategory.RED, checked)
        }
        yellowSwitch.setOnCheckedChangeListener { _, checked ->
            VoiceAlertPrefs.setCategoryEnabled(this, VoiceAlertCategory.YELLOW, checked)
        }
        signalLostSwitch.setOnCheckedChangeListener { _, checked ->
            VoiceAlertPrefs.setCategoryEnabled(this, VoiceAlertCategory.SIGNAL_LOST, checked)
        }
        plateauSwitch.setOnCheckedChangeListener { _, checked ->
            VoiceAlertPrefs.setCategoryEnabled(this, VoiceAlertCategory.PLATEAU, checked)
        }
        correctionSwitch.setOnCheckedChangeListener { _, checked ->
            VoiceAlertPrefs.setCategoryEnabled(this, VoiceAlertCategory.CORRECTION, checked)
        }

        applyMasterState(masterSwitch.isChecked)
    }

    /** Category toggles are meaningless while the master is off, so grey and
     *  disable them - the stored values are preserved and reappear when the
     *  master is turned back on. */
    private fun applyMasterState(masterOn: Boolean) {
        val categories = listOf(redSwitch, yellowSwitch, signalLostSwitch, plateauSwitch, correctionSwitch)
        categories.forEach {
            it.isEnabled = masterOn
            it.alpha = if (masterOn) 1f else 0.4f
        }
        findViewById<android.view.View>(R.id.categoryCard).alpha = if (masterOn) 1f else 0.5f
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, VoiceAlertsActivity::class.java)
    }
}
