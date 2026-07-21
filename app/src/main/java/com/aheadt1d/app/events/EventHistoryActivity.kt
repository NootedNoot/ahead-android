package com.aheadt1d.app.events

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aheadt1d.app.R
import com.google.android.material.datepicker.MaterialDatePicker
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Full chronological browse of every UserEvent ever recorded - GraphActivity's
 * chart overlay (bounded to its own fetch window) and the CSV export
 * (read-only) are the only other access points today. Sourced from
 * UserEventRepository.allEvents (a Room Flow query, already used by
 * EventCsvExporter), so edits/deletes made here or from GraphActivity are
 * reflected everywhere automatically - no manual refresh plumbing needed.
 * Edit/delete reuses EventEditHelper, the same unrestricted path
 * GraphActivity's chart-icon tap already had.
 */
class EventHistoryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var searchInput: EditText
    private lateinit var tagFilterSpinner: Spinner
    private lateinit var dateRangeLabel: TextView
    private lateinit var pickDateRangeButton: Button
    private lateinit var clearDateButton: Button
    private lateinit var sortButton: Button
    private lateinit var emptyStateText: TextView
    private lateinit var adapter: EventHistoryAdapter

    private var allEvents: List<UserEvent> = emptyList()
    private var searchQuery: String = ""
    private var tagFilter: EventTag? = null
    private var dateFilter: Pair<Instant, Instant>? = null
    private var newestFirst = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_event_history)

        findViewById<ImageButton>(R.id.historyBackButton).setOnClickListener { finish() }

        recyclerView = findViewById(R.id.historyRecyclerView)
        searchInput = findViewById(R.id.historySearchInput)
        tagFilterSpinner = findViewById(R.id.historyTagFilterSpinner)
        dateRangeLabel = findViewById(R.id.historyDateRangeLabel)
        pickDateRangeButton = findViewById(R.id.historyPickDateRangeButton)
        clearDateButton = findViewById(R.id.historyClearDateButton)
        sortButton = findViewById(R.id.historySortButton)
        emptyStateText = findViewById(R.id.historyEmptyStateText)

        adapter = EventHistoryAdapter { event -> EventEditHelper.show(this, event) }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        setupTagFilterSpinner()

        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchQuery = s?.toString().orEmpty()
                applyFilters()
            }
        })

        pickDateRangeButton.setOnClickListener { openDateRangePicker() }
        clearDateButton.setOnClickListener {
            dateFilter = null
            updateDateFilterUi()
            applyFilters()
        }
        sortButton.setOnClickListener {
            newestFirst = !newestFirst
            sortButton.setText(if (newestFirst) R.string.notes_sort_newest else R.string.notes_sort_oldest)
            applyFilters()
        }

        observeEvents()
    }

    private fun setupTagFilterSpinner() {
        val labels = listOf(getString(R.string.notes_all_tags)) + EventTag.entries.map { "${it.glyph} ${it.label}" }
        tagFilterSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        tagFilterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                tagFilter = if (position == 0) null else EventTag.entries[position - 1]
                applyFilters()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun observeEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                UserEventRepository.allEvents(applicationContext).collectLatest { events ->
                    allEvents = events
                    applyFilters()
                }
            }
        }
    }

    private fun openDateRangePicker() {
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText(getString(R.string.notes_filter_by_date))
            .build()
        picker.addOnPositiveButtonClickListener { selection ->
            val start = Instant.ofEpochMilli(selection.first)
            val end = Instant.ofEpochMilli(selection.second).plusSeconds(24 * 3600 - 1)
            dateFilter = start to end
            updateDateFilterUi()
            applyFilters()
        }
        picker.show(supportFragmentManager, "notes_date_range")
    }

    private fun updateDateFilterUi() {
        val filter = dateFilter
        if (filter == null) {
            dateRangeLabel.text = getString(R.string.notes_all_dates)
            clearDateButton.visibility = View.GONE
        } else {
            val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.systemDefault())
            dateRangeLabel.text = "${formatter.format(filter.first)} – ${formatter.format(filter.second)}"
            clearDateButton.visibility = View.VISIBLE
        }
    }

    private fun applyFilters() {
        var filtered = allEvents
        tagFilter?.let { tag -> filtered = filtered.filter { it.tag == tag.storageValue } }
        dateFilter?.let { (start, end) ->
            filtered = filtered.filter { it.timestamp in start.toEpochMilli()..end.toEpochMilli() }
        }
        if (searchQuery.isNotBlank()) {
            filtered = filtered.filter { it.note?.contains(searchQuery, ignoreCase = true) == true }
        }
        filtered = if (newestFirst) filtered.sortedByDescending { it.timestamp } else filtered.sortedBy { it.timestamp }

        adapter.submitList(filtered)
        emptyStateText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, EventHistoryActivity::class.java)
    }
}
