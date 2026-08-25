package com.aheadt1d.app.data

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aheadt1d.app.BuildConfig
import com.aheadt1d.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Browses GlucoseVaultDatabase.getDailySummaries() and hands a tapped day's
 * DailyArchiveExporter output straight to the share sheet - see that
 * function's own doc for why sharing skips a preview step for v1. Both
 * classes were already live in production (GlucoseStatusService writes to
 * the vault and exports every check cycle) with no in-app way to reach the
 * result before this screen.
 */
class ArchiveBrowserActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyStateText: TextView
    private lateinit var adapter: ArchiveDayAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_archive_browser)

        findViewById<ImageButton>(R.id.archiveBackButton).setOnClickListener { finish() }

        recyclerView = findViewById(R.id.archiveRecyclerView)
        emptyStateText = findViewById(R.id.archiveEmptyStateText)

        adapter = ArchiveDayAdapter { day -> shareDay(day) }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        loadSummaries()
    }

    private fun loadSummaries() {
        lifecycleScope.launch {
            val summaries = withContext(Dispatchers.IO) {
                GlucoseVaultDatabase.getInstance(applicationContext).getDailySummaries()
            }
            adapter.submitList(summaries)
            emptyStateText.visibility = if (summaries.isEmpty()) View.VISIBLE else View.GONE
            recyclerView.visibility = if (summaries.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun shareDay(day: GlucoseVaultDatabase.DailySummary) {
        lifecycleScope.launch {
            val (jsonFile, csvFile) = withContext(Dispatchers.IO) {
                DailyArchiveExporter.exportDay(applicationContext, LocalDate.parse(day.isoDate))
            }
            if (jsonFile == null && csvFile == null) {
                Toast.makeText(this@ArchiveBrowserActivity, R.string.archive_empty_state, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val authority = "${BuildConfig.APPLICATION_ID}.fileprovider"
            val uris = ArrayList<android.net.Uri>()
            jsonFile?.let { uris.add(FileProvider.getUriForFile(this@ArchiveBrowserActivity, authority, it)) }
            csvFile?.let { uris.add(FileProvider.getUriForFile(this@ArchiveBrowserActivity, authority, it)) }

            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "application/octet-stream"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                putExtra(Intent.EXTRA_SUBJECT, "Ahead archive - ${day.isoDate}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.archive_share_day)))
        }
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, ArchiveBrowserActivity::class.java)
    }
}
