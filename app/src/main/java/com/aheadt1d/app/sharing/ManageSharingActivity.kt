package com.aheadt1d.app.sharing

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.aheadt1d.app.R
import com.aheadt1d.app.network.AuthClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * Owner-side screen only - grant/revoke who can see MY data. Reached from
 * AccountSettingsActivity's "Manage sharing" row. The viewer-side read
 * (GET /api/shares/accessible) lives entirely in ahead-lite-android, not
 * here - this app never needs to know whose data it can view, only who it's
 * granting access to.
 */
class ManageSharingActivity : AppCompatActivity() {

    private lateinit var sharesContainer: LinearLayout
    private lateinit var viewerEmailInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manage_sharing)

        sharesContainer = findViewById(R.id.sharesContainer)
        viewerEmailInput = findViewById(R.id.viewerEmailInput)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.addShareButton).setOnClickListener { addShare() }

        loadShares()
    }

    private fun loadShares() {
        lifecycleScope.launch {
            try {
                renderShares(SharingClient.fetchShares(this@ManageSharingActivity))
            } catch (e: AuthClient.SessionExpiredException) {
                Toast.makeText(this@ManageSharingActivity, e.message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load shares", e)
                Toast.makeText(this@ManageSharingActivity, "Couldn't load your sharing list - check your connection", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun renderShares(shares: JSONArray) {
        sharesContainer.removeAllViews()
        val dateFormatter = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

        for (i in 0 until shares.length()) {
            val share = shares.getJSONObject(i)
            val row = LayoutInflater.from(this).inflate(R.layout.item_share, sharesContainer, false)
            val shareId = share.getString("shareId")

            row.findViewById<TextView>(R.id.shareViewerEmail).text = share.getString("viewerEmail")
            row.findViewById<TextView>(R.id.shareMeta).text =
                "Since ${dateFormatter.format(Date(java.time.Instant.parse(share.getString("createdAt")).toEpochMilli()))}"
            row.findViewById<TextView>(R.id.shareRevokeButton).setOnClickListener {
                confirmRevoke(shareId, share.getString("viewerEmail"))
            }

            sharesContainer.addView(row)
        }

        if (shares.length() == 0) {
            val empty = TextView(this).apply {
                text = "Nobody yet - add someone above to let them follow your glucose data."
                setTextColor(getColor(R.color.muted))
                textSize = 13f
            }
            sharesContainer.addView(empty)
        }
    }

    private fun addShare() {
        val email = viewerEmailInput.text.toString().trim()
        if (email.isBlank()) {
            Toast.makeText(this, "Enter an email address", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                SharingClient.createShare(this@ManageSharingActivity, email)
                viewerEmailInput.setText("")
                Toast.makeText(this@ManageSharingActivity, "Shared with $email", Toast.LENGTH_SHORT).show()
                loadShares()
            } catch (e: Exception) {
                // The backend's own message is already exactly what the user
                // needs here - "No account found for that email..." etc -
                // surfaced directly, not reworded.
                Toast.makeText(this@ManageSharingActivity, e.message ?: "Couldn't grant access", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmRevoke(shareId: String, viewerEmail: String) {
        AlertDialog.Builder(this)
            .setTitle("Revoke access?")
            .setMessage("$viewerEmail will no longer be able to see your glucose data.")
            .setPositiveButton("Revoke") { _, _ ->
                lifecycleScope.launch {
                    try {
                        SharingClient.revokeShare(this@ManageSharingActivity, shareId)
                        loadShares()
                    } catch (e: Exception) {
                        Toast.makeText(this@ManageSharingActivity, e.message ?: "Couldn't revoke access", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        private const val TAG = "ManageSharingActivity"

        fun createIntent(context: Context): Intent = Intent(context, ManageSharingActivity::class.java)
    }
}
