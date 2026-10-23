package com.makryden.docs

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Notes-specific RecyclerView adapter.
 * Shows title, snippet, and formatted date for each note.
 */
class NoteAdapter(
    private val onClick: (SupabaseClient.DocItem) -> Unit
) : ListAdapter<SupabaseClient.DocItem, NoteAdapter.NoteViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SupabaseClient.DocItem>() {
            override fun areItemsTheSame(
                oldItem: SupabaseClient.DocItem,
                newItem: SupabaseClient.DocItem
            ): Boolean = oldItem.id == newItem.id

            override fun areContentsTheSame(
                oldItem: SupabaseClient.DocItem,
                newItem: SupabaseClient.DocItem
            ): Boolean = oldItem == newItem
        }
    }

    inner class NoteViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: TextView = view.findViewById(R.id.note_icon)
        private val titleView: TextView = view.findViewById(R.id.note_title)
        private val snippetView: TextView = view.findViewById(R.id.note_snippet)
        private val dateView: TextView = view.findViewById(R.id.note_date)

        fun bind(item: SupabaseClient.DocItem) {
            // Pencil glyph (U+270E) as the note icon
            icon.text = "\u270E"
            titleView.text = item.title.ifEmpty { "Untitled" }
            if (item.snippet.isEmpty()) {
                snippetView.visibility = View.GONE
            } else {
                snippetView.visibility = View.VISIBLE
                snippetView.text = item.snippet
            }
            val formatted = formatDate(item.updatedAt)
            if (formatted.isEmpty()) {
                dateView.visibility = View.GONE
            } else {
                dateView.visibility = View.VISIBLE
                dateView.text = formatted
            }
            itemView.setOnClickListener { onClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_note, parent, false)
        return NoteViewHolder(view)
    }

    override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /**
     * Format an ISO-8601 timestamp as "MMM d, yyyy".
     * Returns empty string if parsing fails.
     */
    private fun formatDate(iso: String): String {
        if (iso.isEmpty()) return ""
        return try {
            val clean = iso.substringBefore(".").substringBefore("+")
                .substringBefore("Z").trim()
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            parser.timeZone = TimeZone.getTimeZone("UTC")
            val date = parser.parse(clean) ?: return ""
            val formatter = SimpleDateFormat("MMM d, yyyy", Locale.US)
            formatter.timeZone = TimeZone.getDefault()
            formatter.format(date)
        } catch (e: Exception) {
            ""
        }
    }
}
