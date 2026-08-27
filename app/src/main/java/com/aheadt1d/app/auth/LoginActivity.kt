package com.aheadt1d.app.auth

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.aheadt1d.app.MainActivity
import com.aheadt1d.app.R
import com.aheadt1d.app.network.AuthClient
import kotlinx.coroutines.launch

/**
 * The one and only login/signup screen - no separate SignupActivity, just a
 * mode toggle (see MODE_LOGIN/MODE_SIGNUP below) that swaps field visibility
 * and the submit button's label. This is the gate MainActivity.onCreate
 * redirects to whenever AuthPrefs.isSetUp() is false - see that function's
 * doc for why that's "no device key stored," not "no valid session."
 *
 * On success (either mode): mints a device API key immediately, persists
 * everything, and launches MainActivity fresh with CLEAR_TASK so this
 * screen (and any half-built back stack under it) can never be returned to
 * with the back button.
 */
class LoginActivity : AppCompatActivity() {

    private var mode = MODE_LOGIN

    private lateinit var modeSubtitle: TextView
    private lateinit var displayNameGroup: View
    private lateinit var displayNameInput: EditText
    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var errorText: TextView
    private lateinit var submitButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var toggleModeLink: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        modeSubtitle = findViewById(R.id.modeSubtitle)
        displayNameGroup = findViewById(R.id.displayNameGroup)
        displayNameInput = findViewById(R.id.displayNameInput)
        emailInput = findViewById(R.id.emailInput)
        passwordInput = findViewById(R.id.passwordInput)
        errorText = findViewById(R.id.errorText)
        submitButton = findViewById(R.id.submitButton)
        progressBar = findViewById(R.id.progressBar)
        toggleModeLink = findViewById(R.id.toggleModeLink)

        toggleModeLink.setOnClickListener {
            mode = if (mode == MODE_LOGIN) MODE_SIGNUP else MODE_LOGIN
            applyMode()
        }
        submitButton.setOnClickListener { submit() }
        applyMode()
    }

    private fun applyMode() {
        errorText.visibility = View.GONE
        if (mode == MODE_LOGIN) {
            modeSubtitle.text = "Log in to your account"
            displayNameGroup.visibility = View.GONE
            submitButton.text = "Log in"
            toggleModeLink.text = "New here? Create an account"
        } else {
            modeSubtitle.text = "Create your account"
            displayNameGroup.visibility = View.VISIBLE
            submitButton.text = "Sign up"
            toggleModeLink.text = "Already have an account? Log in"
        }
    }

    private fun submit() {
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString()
        val displayName = displayNameInput.text.toString().trim()

        if (email.isBlank() || password.isBlank()) {
            showError("Email and password are required")
            return
        }
        if (mode == MODE_SIGNUP && password.length < 10) {
            showError("Password must be at least 10 characters")
            return
        }

        setLoading(true)
        lifecycleScope.launch {
            try {
                if (mode == MODE_LOGIN) {
                    AuthClient.login(this@LoginActivity, email, password)
                } else {
                    AuthClient.signup(this@LoginActivity, email, password, displayName.ifBlank { null })
                }
                // Device label helps distinguish devices in Account Settings'
                // device list and the admin panel - best-effort, a real
                // model name beats a generic default.
                val label = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
                AuthClient.mintDevice(this@LoginActivity, label.ifBlank { null })

                startActivity(
                    Intent(this@LoginActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                finish()
            } catch (e: AuthClient.AuthException) {
                setLoading(false)
                showError(e.message ?: "Something went wrong")
            } catch (e: Exception) {
                setLoading(false)
                showError("Couldn't reach the server - check your connection and try again")
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        submitButton.isEnabled = !loading
        toggleModeLink.isEnabled = !loading
    }

    private fun showError(message: String) {
        errorText.text = message
        errorText.visibility = View.VISIBLE
    }

    companion object {
        private const val MODE_LOGIN = 0
        private const val MODE_SIGNUP = 1

        fun createIntent(context: Context): Intent = Intent(context, LoginActivity::class.java)
    }
}
