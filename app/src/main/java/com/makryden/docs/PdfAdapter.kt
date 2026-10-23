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
 * Dedicated adapter for the PDF list. Shows filename, file size, and date.
 * Works with SupabaseClient.DocItem where kind == "pdf".
 */
class PdfAdapter(
    private val onClick: (SupabaseClient.DocItem) -> Unit
) : ListAdapter<SupabaseClient.DocItem, PdfAdapter.ViewHolder>(DiffCallback()) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: TextView = view.findViewById(R.id.pdf_icon)
        val filename: TextView = view.findViewById(R.id.pdf_filename)
        val meta: TextView = view.findViewById(R.id.pdf_meta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pdf, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        try {
            val item = getItem(position)
            holder.filename.text = item.title.ifEmpty { "Untitled PDF" }
            // snippet holds the formatted size from SupabaseClient.getPdfs()
            val size = item.snippet
            val date = formatDate(item.updatedAt)
            holder.meta.text = listOf(size, date).filter { it.isNotEmpty() }.joinToString("  \u00B7  ")
            holder.meta.visibility = if (holder.meta.text.isEmpty()) View.GONE else View.VISIBLE
            holder.icon.text = "\uD83D\uDCC4"
            holder.itemView.setOnClickListener { onClick(item) }
        } catch (e: Exception) {
            android.util.Log.w("PdfAdapter", "Bind failed at position $position", e)
        }
    }

    private fun formatDate(iso: String): String {
        if (iso.isEmpty()) return ""
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            parser.timeZone = TimeZone.getTimeZone("UTC")
            val clean = iso.substringBefore(".").substringBefore("+").substringBefore("Z")
            val date = parser.parse(clean) ?: return ""
            SimpleDateFormat("MMM d, yyyy", Locale.US).format(date)
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
