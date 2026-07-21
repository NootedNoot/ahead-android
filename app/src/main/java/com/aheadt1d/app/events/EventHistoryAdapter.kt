package com.aheadt1d.app.events

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aheadt1d.app.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class EventHistoryAdapter(
    private val onRowClick: (UserEvent) -> Unit
) : RecyclerView.Adapter<EventHistoryAdapter.ViewHolder>() {

    private var items: List<UserEvent> = emptyList()
    private val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a").withZone(ZoneId.systemDefault())

    fun submitList(newItems: List<UserEvent>) {
        items = newItems
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val glyph: TextView = view.findViewById(R.id.itemEventGlyph)
        val dateTime: TextView = view.findViewById(R.id.itemEventDateTime)
        val tagLabel: TextView = view.findViewById(R.id.itemEventTagLabel)
        val note: TextView = view.findViewById(R.id.itemEventNote)
        val glucose: TextView = view.findViewById(R.id.itemEventGlucose)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_event_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val event = items[position]
        val tag = EventTag.fromStorageValue(event.tag)
        holder.glyph.text = tag.glyph
        holder.dateTime.text = formatter.format(Instant.ofEpochMilli(event.timestamp))
        holder.tagLabel.text = tag.label
        holder.note.text = event.note ?: ""
        holder.note.visibility = if (event.note.isNullOrBlank()) View.GONE else View.VISIBLE
        holder.glucose.text = event.glucoseAtTime?.let { "${it.toInt()} mg/dL" } ?: ""
        holder.itemView.setOnClickListener { onRowClick(event) }
    }

    override fun getItemCount(): Int = items.size
}
