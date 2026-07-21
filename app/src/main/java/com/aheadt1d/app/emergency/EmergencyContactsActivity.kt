package com.aheadt1d.app.emergency

import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aheadt1d.app.R
import kotlinx.coroutines.launch

/**
 * Settings screen for the Emergency Contact Alert feature: master on/off,
 * cooldown + the name substituted into the SMS body, and the contact list
 * itself (added via the system contact picker - ACTION_PICK against the
 * Phone content type grants read access to just the picked row, so this
 * never needs to request READ_CONTACTS).
 */
class EmergencyContactsActivity : AppCompatActivity() {

    private lateinit var masterSwitch: SwitchCompat
    private lateinit var userNameInput: EditText
    private lateinit var cooldownInput: EditText
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_emergency_contacts)

        findViewById<TextView>(R.id.backButton).setOnClickListener { finish() }

        masterSwitch = findViewById(R.id.masterSwitch)
        userNameInput = findViewById(R.id.userNameInput)
        cooldownInput = findViewById(R.id.cooldownInput)
        contactListContainer = findViewById(R.id.contactListContainer)

        masterSwitch.isChecked = EmergencyContactsPrefs.isEnabled(this)
        userNameInput.setText(EmergencyContactsPrefs.userName(this))
        cooldownInput.setText(EmergencyContactsPrefs.cooldownMinutes(this).toString())

        masterSwitch.setOnCheckedChangeListener { _, checked ->
            EmergencyContactsPrefs.setEnabled(this, checked)
        }

        findViewById<Button>(R.id.saveSettingsButton).setOnClickListener { saveSettings() }
        findViewById<Button>(R.id.addContactButton).setOnClickListener { launchContactPicker() }

        observeContacts()
    }

    private fun saveSettings() {
        val name = userNameInput.text.toString()
        if (name.isNotBlank()) EmergencyContactsPrefs.setUserName(this, name)
        cooldownInput.text.toString().toIntOrNull()?.let {
            EmergencyContactsPrefs.setCooldownMinutes(this, it)
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
