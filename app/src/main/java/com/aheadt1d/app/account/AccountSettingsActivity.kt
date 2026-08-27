package com.aheadt1d.app.account

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.aheadt1d.app.R
import com.aheadt1d.app.auth.AuthPrefs
import com.aheadt1d.app.auth.LoginActivity
import com.aheadt1d.app.network.AuthClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mirrors upload/UploadSettingsActivity's visual pattern (ScrollView, manual
 * back-chevron header, card sections, Toast-only feedback) - no inline-
 * error-on-EditText precedent exists anywhere in this app, so this doesn't
 * invent one either.
 */
class AccountSettingsActivity : AppCompatActivity() {

    private lateinit var emailText: TextView
    private lateinit var devicesContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account_settings)

        emailText = findViewById(R.id.accountEmailText)
        devicesContainer = findViewById(R.id.devicesContainer)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        emailText.text = AuthPrefs.email(this) ?: "—"

        findViewById<View>(R.id.manageSharingRow).setOnClickListener {
            startActivity(com.aheadt1d.app.sharing.ManageSharingActivity.createIntent(this))
        }

        findViewById<View>(R.id.logOutButton).setOnClickListener { confirmLogOut() }
        findViewById<View>(R.id.deleteAccountButton).setOnClickListener { promptDeleteAccount() }

        loadDevices()
    }

    private fun loadDevices() {
        lifecycleScope.launch {
            try {
                val devices = AuthClient.fetchDevices(this@AccountSettingsActivity)
                renderDevices(devices)
            } catch (e: AuthClient.SessionExpiredException) {
                Toast.makeText(this@AccountSettingsActivity, e.message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@AccountSettingsActivity, "Couldn't load devices - check your connection", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun renderDevices(devices: JSONArray) {
        devicesContainer.removeAllViews()
        val thisDeviceId = AuthPrefs.deviceId(this)
        val timeFormatter = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

        for (i in 0 until devices.length()) {
            val device = devices.getJSONObject(i)
            val row = LayoutInflater.from(this).inflate(R.layout.item_account_device, devicesContainer, false)
            val deviceId = device.getString("deviceId")
            val isThisDevice = deviceId == thisDeviceId

            row.findViewById<TextView>(R.id.deviceLabel).text =
                (device.optString("label", null) ?: "Unnamed device") + if (isThisDevice) " (this device)" else ""

            val lastUsed = device.optString("lastUsedAt", null)
            val revoked = device.optBoolean("revoked", false)
            row.findViewById<TextView>(R.id.deviceMeta).text = when {
                revoked -> "Revoked"
                lastUsed != null -> "Last used ${timeFormatter.format(Date(java.time.Instant.parse(lastUsed).toEpochMilli()))}"
                else -> "Never used yet"
            }

            val revokeButton = row.findViewById<TextView>(R.id.deviceRevokeButton)
            if (revoked) {
                revokeButton.visibility = View.GONE
            } else {
                revokeButton.setOnClickListener { confirmRevokeDevice(deviceId, isThisDevice) }
            }

            devicesContainer.addView(row)
        }

        if (devices.length() == 0) {
            val empty = TextView(this).apply {
                text = "No devices yet."
                setTextColor(getColor(R.color.muted))
                textSize = 13f
            }
            devicesContainer.addView(empty)
        }
    }

    private fun confirmRevokeDevice(deviceId: String, isThisDevice: Boolean) {
        val message = if (isThisDevice) {
            "This is the device you're using right now - revoking it will stop uploads here too, same as logging out."
        } else {
            "That device will stop being able to upload immediately."
        }
        AlertDialog.Builder(this)
            .setTitle("Revoke this device?")
            .setMessage(message)
            .setPositiveButton("Revoke") { _, _ ->
                lifecycleScope.launch {
                    try {
                        AuthClient.revokeDevice(this@AccountSettingsActivity, deviceId)
                        if (isThisDevice) {
                            AuthPrefs.clear(this@AccountSettingsActivity)
                            goToLogin()
                        } else {
                            loadDevices()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this@AccountSettingsActivity, e.message ?: "Couldn't revoke that device", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmLogOut() {
        AlertDialog.Builder(this)
            .setTitle("Log out?")
            .setMessage("This device will stop uploading glucose readings until you log back in.")
            .setPositiveButton("Log out") { _, _ ->
                lifecycleScope.launch {
                    // Best-effort - revoke this device's key server-side so a
                    // lost/stolen phone's old key can't keep uploading, but
                    // don't let a network failure trap the user mid-logout.
                    val deviceId = AuthPrefs.deviceId(this@AccountSettingsActivity)
                    if (deviceId != null) {
                        runCatching { AuthClient.revokeDevice(this@AccountSettingsActivity, deviceId) }
                    }
                    AuthPrefs.clear(this@AccountSettingsActivity)
                    goToLogin()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptDeleteAccount() {
        val input = EditText(this).apply {
            hint = "Your password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        AlertDialog.Builder(this)
            .setTitle("Delete your account?")
            .setMessage("This permanently removes your glucose history, devices, and sharing grants. Enter your password to confirm - this can't be undone.")
            .setView(container)
            .setPositiveButton("Delete forever") { _, _ ->
                val password = input.text.toString()
                if (password.isBlank()) {
                    Toast.makeText(this, "Password is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                deleteAccount(password)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteAccount(password: String) {
        lifecycleScope.launch {
            try {
                AuthClient.deleteAccount(this@AccountSettingsActivity, password)
                AuthPrefs.clear(this@AccountSettingsActivity)
                Toast.makeText(this@AccountSettingsActivity, "Account deleted", Toast.LENGTH_LONG).show()
                goToLogin()
            } catch (e: Exception) {
                Toast.makeText(this@AccountSettingsActivity, e.message ?: "Couldn't delete account", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun goToLogin() {
        startActivity(
            LoginActivity.createIntent(this)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, AccountSettingsActivity::class.java)
    }
}
