package com.aheadt1d.app.emergency

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aheadt1d.app.R
import kotlinx.coroutines.launch

/**
 * Settings screen for the Emergency Contact Alert feature: master on/off,
 * the cooldown and auto-text-delay timings, the name substituted into the
 * SMS body, and the contact list itself (added via the system contact picker -
 * ACTION_PICK against the Phone content type grants read access to just the
 * picked row, so this never needs to request READ_CONTACTS).
 *
 * Two distinct "minutes" settings live here on purpose, not one: cooldownInput
 * paces REPEAT texts to the same contact after a send already happened;
 * alertTimeoutInput controls how long a red alert may go unacknowledged
 * before the FIRST auto-text fires (EmergencyAlertScheduler/AlertCoordinator).
 * They defaulted to the same number (15) before alertTimeoutInput existed as
 * its own field, which risked this screen implying a single "cooldown" value
 * controlled both.
 *
 * Turning the master switch ON is this feature's explicit SEND_SMS opt-in
 * moment - deliberately here, not requested silently at app launch, and not
 * deferred to alert time: the automatic unacknowledged-timeout send (see
 * EmergencyAlertReceiver) fires from a background receiver with no UI to
 * prompt from, so permission has to already be settled well before any red
 * alert ever fires. This screen also re-checks the REAL grant on every
 * onResume() (smsPermissionStatus) - it can be revoked externally at any time
 * without the persisted "enabled" flag ever knowing, and a silently-broken
 * automatic safety feature is worse than a merely-off one. RedAlertActivity's
 * manual "Send Emergency Alert" button still has its own independent
 * request/rationale too, for someone who enables the feature (or upgrades to
 * this version) without ever visiting this screen again.
 */
class EmergencyContactsActivity : AppCompatActivity() {

    private lateinit var masterSwitch: SwitchCompat
    private lateinit var smsPermissionStatus: TextView
    private lateinit var userNameInput: EditText
    private lateinit var cooldownInput: EditText
    private lateinit var alertTimeoutInput: EditText
    private lateinit var contactListContainer: LinearLayout

    private val pickContact = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        val (name, number) = queryPickedContact(uri) ?: run {
            Toast.makeText(this, "Couldn't read that contact", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        lifecycleScope.launch { EmergencyAlertRepository.addContact(this@EmergencyContactsActivity, name, number) }
    }

    private val requestSmsPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            EmergencyContactsPrefs.setEnabled(this, true)
        } else {
            Toast.makeText(this, getString(R.string.emergency_sms_denied), Toast.LENGTH_LONG).show()
            // Revert the toggle - without SEND_SMS the feature can't do
            // anything, so leaving it visually "on" would be misleading.
            masterSwitch.isChecked = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_emergency_contacts)

        findViewById<TextView>(R.id.backButton).setOnClickListener { finish() }

        masterSwitch = findViewById(R.id.masterSwitch)
        smsPermissionStatus = findViewById(R.id.smsPermissionStatus)
        userNameInput = findViewById(R.id.userNameInput)
        cooldownInput = findViewById(R.id.cooldownInput)
        alertTimeoutInput = findViewById(R.id.alertTimeoutInput)
        contactListContainer = findViewById(R.id.contactListContainer)

        masterSwitch.isChecked = EmergencyContactsPrefs.isEnabled(this)
        userNameInput.setText(EmergencyContactsPrefs.userName(this))
        cooldownInput.setText(EmergencyContactsPrefs.cooldownMinutes(this).toString())
        alertTimeoutInput.setText(EmergencyContactsPrefs.alertTimeoutMinutes(this).toString())

        masterSwitch.setOnCheckedChangeListener { _, checked -> onMasterSwitchChanged(checked) }
        smsPermissionStatus.setOnClickListener { onMasterSwitchChanged(true) }

        findViewById<Button>(R.id.saveSettingsButton).setOnClickListener { saveSettings() }
        findViewById<Button>(R.id.addContactButton).setOnClickListener { launchContactPicker() }

        observeContacts()
    }

    // Catches SEND_SMS being revoked externally (system Settings) at any
    // time after the master switch was turned on - onCreate alone would only
    // ever see the state as of the moment this screen was first opened.
    // Mirrors the exact same "regression, not just absence" pattern
    // MainActivity's DND-access banner already uses.
    override fun onResume() {
        super.onResume()
        updateSmsPermissionStatus()
    }

    private fun updateSmsPermissionStatus() {
        if (!masterSwitch.isChecked) {
            smsPermissionStatus.visibility = android.view.View.GONE
            return
        }
        val granted = ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        smsPermissionStatus.visibility = android.view.View.VISIBLE
        if (granted) {
            smsPermissionStatus.text = getString(R.string.emergency_sms_permission_granted)
            smsPermissionStatus.setTextColor(getColorCompat(R.color.muted))
            smsPermissionStatus.isClickable = false
        } else {
            smsPermissionStatus.text = getString(R.string.emergency_sms_permission_missing)
            smsPermissionStatus.setTextColor(getColorCompat(R.color.low))
            smsPermissionStatus.isClickable = true
        }
    }

    /** Turning ON while SEND_SMS isn't granted yet is this feature's one
     *  explicit opt-in moment (see class doc) - show the rationale, then the
     *  system prompt, and only persist "enabled" once permission is actually
     *  confirmed granted (in the requestSmsPermission callback above).
     *  Turning OFF (or turning ON when already granted) persists immediately,
     *  same as before this existed. Also reachable from tapping the "SMS
     *  permission revoked" status line, which re-runs this same check/prompt
     *  without requiring the user to toggle the switch off and back on first. */
    private fun onMasterSwitchChanged(checked: Boolean) {
        if (checked && ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            AlertDialog.Builder(this)
                .setTitle("SMS permission needed")
                .setMessage(getString(R.string.emergency_sms_rationale, EmergencyContactsPrefs.alertTimeoutMinutes(this)))
                .setPositiveButton("Continue") { _, _ -> requestSmsPermission.launch(Manifest.permission.SEND_SMS) }
                .setNegativeButton("Cancel") { _, _ -> masterSwitch.isChecked = false }
                .show()
            return
        }
        EmergencyContactsPrefs.setEnabled(this, checked)
        // Turning the feature off should also stand down anything already
        // counting down - EmergencyAlertReceiver re-checks isEnabled() at
        // fire time regardless (so nothing would actually send either way),
        // but a countdown left ticking after the user explicitly turned this
        // off is exactly the kind of "setting doesn't really do what it
        // says" gap worth closing outright, not just defending against.
        if (!checked) EmergencyAlertScheduler.cancel(this)
        updateSmsPermissionStatus()
    }

    private fun saveSettings() {
        val name = userNameInput.text.toString()
        if (name.isNotBlank()) EmergencyContactsPrefs.setUserName(this, name)
        cooldownInput.text.toString().toIntOrNull()?.let {
            EmergencyContactsPrefs.setCooldownMinutes(this, it)
        }
        alertTimeoutInput.text.toString().toLongOrNull()?.let {
            EmergencyContactsPrefs.setAlertTimeoutMinutes(this, it)
        }
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
    }

    private fun launchContactPicker() {
        pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
    }

    /** Reads DISPLAY_NAME + NUMBER off the picked row. The Uri returned by an
     *  ACTION_PICK against Phone.CONTENT_URI carries its own temporary read
     *  grant, so this works without holding READ_CONTACTS. */
    private fun queryPickedContact(uri: android.net.Uri): Pair<String, String>? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (nameIndex < 0 || numberIndex < 0) return null
            val name = cursor.getString(nameIndex) ?: return null
            val number = cursor.getString(numberIndex) ?: return null
            return name to number
        }
        return null
    }

    private fun observeContacts() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                EmergencyAlertRepository.contacts(this@EmergencyContactsActivity).collect { contacts ->
                    renderContacts(contacts)
                }
            }
        }
    }

    private fun renderContacts(contacts: List<EmergencyContact>) {
        contactListContainer.removeAllViews()
        if (contacts.isEmpty()) {
            contactListContainer.addView(TextView(this).apply {
                text = getString(R.string.emergency_no_contacts)
                setTextColor(getColorCompat(R.color.muted))
                textSize = 12f
            })
            return
        }
        for (contact in contacts) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                params.topMargin = dp(8)
                layoutParams = params
            }
            val label = TextView(this).apply {
                text = "${contact.name}\n${contact.phoneNumber}"
                setTextColor(getColorCompat(R.color.text_primary))
                textSize = 14f
                val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                layoutParams = params
            }
            val removeButton = Button(this).apply {
                text = getString(R.string.emergency_remove)
                isAllCaps = false
                setBackgroundResource(R.drawable.time_btn_inactive)
                setTextColor(getColorCompat(R.color.low))
                setOnClickListener {
                    lifecycleScope.launch { EmergencyAlertRepository.removeContact(this@EmergencyContactsActivity, contact) }
                }
            }
            row.addView(label)
            row.addView(removeButton)
            contactListContainer.addView(row)
        }
    }

    private fun getColorCompat(resId: Int) = androidx.core.content.ContextCompat.getColor(this, resId)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        fun createIntent(context: android.content.Context): Intent =
            Intent(context, EmergencyContactsActivity::class.java)
    }
}
