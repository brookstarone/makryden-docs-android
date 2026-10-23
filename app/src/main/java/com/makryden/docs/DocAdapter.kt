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
 * Native RecyclerView adapter for document/note/PDF list rows.
 */
class DocAdapter(
    private val onClick: (SupabaseClient.DocItem) -> Unit
) : ListAdapter<SupabaseClient.DocItem, DocAdapter.ViewHolder>(DiffCallback()) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: TextView = view.findViewById(R.id.item_icon)
        val title: TextView = view.findViewById(R.id.item_title)
        val subtitle: TextView = view.findViewById(R.id.item_subtitle)
        val date: TextView = view.findViewById(R.id.item_date)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_doc, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.title.text = item.title
        holder.subtitle.text = item.snippet
        holder.subtitle.visibility = if (item.snippet.isEmpty()) View.GONE else View.VISIBLE
        holder.date.text = formatDate(item.updatedAt)
        holder.icon.text = when (item.kind) {
            "doc" -> "\uD83D\uDCDD" // memo
            "note" -> "\uD83D\uDCDD" // memo
            "pdf" -> "\uD83D\uDCC4" // page
            else -> "\uD83D\uDCC4"
        }
        holder.itemView.setOnClickListener { onClick(item) }
    }

    private fun formatDate(iso: String): String {
        if (iso.isEmpty()) return ""
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            parser.timeZone = TimeZone.getTimeZone("UTC")
            // Handle fractional seconds and timezone suffixes
            val clean = iso.substringBefore(".").substringBefore("+").substringBefore("Z")
            val date = parser.parse(clean) ?: return ""
            val formatter = SimpleDateFormat("MMM d, yyyy", Locale.US)
            formatter.format(date)
        } catch (e: Exception) {
            ""
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<SupabaseClient.DocItem>() {
        override fun areItemsTheSame(old: SupabaseClient.DocItem, new: SupabaseClient.DocItem): Boolean {
            return old.id == new.id
        }
        override fun areContentsTheSame(old: SupabaseClient.DocItem, new: SupabaseClient.DocItem): Boolean {
            return old == new
        }
    }
}
