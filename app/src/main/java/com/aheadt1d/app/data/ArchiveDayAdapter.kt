package com.aheadt1d.app.data

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aheadt1d.app.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ArchiveDayAdapter(
    private val onShareClick: (GlucoseVaultDatabase.DailySummary) -> Unit,
) : RecyclerView.Adapter<ArchiveDayAdapter.ViewHolder>() {

    private var items: List<GlucoseVaultDatabase.DailySummary> = emptyList()
    private val dateFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy").withZone(ZoneId.systemDefault())
    private val timeFormatter = DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault())

    fun submitList(newItems: List<GlucoseVaultDatabase.DailySummary>) {
        items = newItems
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val date: TextView = view.findViewById(R.id.itemArchiveDate)
        val meta: TextView = view.findViewById(R.id.itemArchiveMeta)
        val share: TextView = view.findViewById(R.id.itemArchiveShare)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_archive_day, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val day = items[position]
        val firstInstant = Instant.ofEpochMilli(day.firstMillis)
        holder.date.text = dateFormatter.format(firstInstant)
        holder.meta.text = "${day.count} readings · ${timeFormatter.format(firstInstant)}–${timeFormatter.format(Instant.ofEpochMilli(day.lastMillis))}"
        holder.share.setOnClickListener { onShareClick(day) }
        holder.itemView.setOnClickListener { onShareClick(day) }
    }

    override fun getItemCount(): Int = items.size
}
