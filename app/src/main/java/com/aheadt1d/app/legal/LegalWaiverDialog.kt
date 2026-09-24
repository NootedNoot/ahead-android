package com.aheadt1d.app.legal

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.aheadt1d.app.BuildConfig
import com.aheadt1d.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Mandatory, legally binding Medical Disclaimer, Liability Waiver, and
 * Covenant Not to Sue dialog.
 *
 * Enforces an active, click-through agreement where the user must check the
 * acknowledgment box before the agreement button activates.
 */
object LegalWaiverDialog {

    private const val ONLINE_TERMS_URL = "https://aheadt1d.com/legal.html"

    /**
     * Displays the mandatory blocking waiver if the user hasn't accepted it yet.
     * The dialog cannot be canceled or dismissed by tapping outside or pressing Back.
     */
    fun showMandatory(activity: Activity, onAccepted: () -> Unit) {
        if (LegalPrefs.hasAcceptedWaiver(activity)) {
            onAccepted()
            return
        }

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_legal_waiver, null)
        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setCancelable(false)
            .create()

        dialog.setCanceledOnTouchOutside(false)

        val checkbox = view.findViewById<CheckBox>(R.id.waiverCheckbox)
        val checkboxLabel = view.findViewById<TextView>(R.id.waiverCheckboxLabel)
        val agreeButton = view.findViewById<Button>(R.id.agreeWaiverButton)
        val declineButton = view.findViewById<Button>(R.id.declineWaiverButton)
        val onlineTermsLink = view.findViewById<TextView>(R.id.viewOnlineTermsButton)

        fun updateAgreeState(isChecked: Boolean) {
            agreeButton.isEnabled = isChecked
            agreeButton.alpha = if (isChecked) 1.0f else 0.5f
        }

        checkbox.setOnCheckedChangeListener { _, isChecked -> updateAgreeState(isChecked) }
        checkboxLabel.setOnClickListener { checkbox.isChecked = !checkbox.isChecked }

        declineButton.setOnClickListener {
            dialog.dismiss()
            activity.finishAffinity()
        }

        agreeButton.setOnClickListener {
            if (checkbox.isChecked) {
                LegalPrefs.recordWaiverAccepted(activity, BuildConfig.VERSION_NAME)
                dialog.dismiss()
                onAccepted()
            }
        }

        onlineTermsLink.setOnClickListener {
            runCatching {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ONLINE_TERMS_URL)))
            }
        }

        dialog.show()
    }

    /**
     * Displays the legal waiver in review mode (accessible from Navigation Drawer).
     */
    fun showReview(activity: Activity) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_legal_waiver, null)
        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setCancelable(true)
            .create()

        val subtitle = view.findViewById<TextView>(R.id.waiverSubtitle)
        val checkboxContainer = view.findViewById<View>(R.id.agreementCheckboxContainer)
        val declineButton = view.findViewById<Button>(R.id.declineWaiverButton)
        val agreeButton = view.findViewById<Button>(R.id.agreeWaiverButton)
        val onlineTermsLink = view.findViewById<TextView>(R.id.viewOnlineTermsButton)

        val timestamp = LegalPrefs.getAcceptedTimestamp(activity)
        val version = LegalPrefs.getAcceptedVersion(activity) ?: BuildConfig.VERSION_NAME
        val dateStr = if (timestamp > 0) {
            SimpleDateFormat("MMMM d, yyyy", Locale.US).format(Date(timestamp))
        } else {
            "Active"
        }

        subtitle.text = "Accepted on $dateStr (v$version) • Legally Binding"
        checkboxContainer.visibility = View.GONE
        declineButton.visibility = View.GONE

        agreeButton.text = "Done"
        agreeButton.isEnabled = true
        agreeButton.alpha = 1.0f
        agreeButton.setOnClickListener { dialog.dismiss() }

        onlineTermsLink.setOnClickListener {
            runCatching {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ONLINE_TERMS_URL)))
            }
        }

        dialog.show()
    }
}
