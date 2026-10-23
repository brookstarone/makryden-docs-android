package com.makryden.docs

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton

/**
 * Base fragment for native document/note/PDF lists.
 * Subclasses provide loadItems() and createNew().
 */
abstract class BaseListFragment : Fragment() {

    protected lateinit var adapter: DocAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private lateinit var emptyView: TextView

    abstract fun loadItems(token: String): List<SupabaseClient.DocItem>
    abstract fun createNew(token: String, userId: String): SupabaseClient.DocItem?
    abstract fun emptyText(): String

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recycler = view.findViewById(R.id.recycler)
        swipeRefresh = view.findViewById(R.id.swipe_refresh)
        progress = view.findViewById(R.id.progress)
        emptyView = view.findViewById(R.id.empty_view)
        val fab: FloatingActionButton = view.findViewById(R.id.fab)

        emptyView.text = emptyText()

        adapter = DocAdapter { item ->
            openEditor(item)
        }
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        swipeRefresh.setOnRefreshListener { refresh() }
        fab.setOnClickListener { createItem() }

        refresh()
    }

    private fun openEditor(item: SupabaseClient.DocItem) {
        val intent = when (item.kind) {
            "note" -> Intent(requireContext(), NoteEditorActivity::class.java).apply {
                putExtra(NoteEditorActivity.EXTRA_ID, item.id)
                putExtra(NoteEditorActivity.EXTRA_TITLE, item.title)
            }
            "pdf" -> Intent(requireContext(), PdfViewerActivity::class.java).apply {
                putExtra(PdfViewerActivity.EXTRA_ID, item.id)
                putExtra(PdfViewerActivity.EXTRA_TITLE, item.title)
            }
            else -> Intent(requireContext(), WriterEditorActivity::class.java).apply {
                putExtra(WriterEditorActivity.EXTRA_ID, item.id)
                putExtra(WriterEditorActivity.EXTRA_TITLE, item.title)
            }
        }
        startActivity(intent)
    }

    fun refresh() {
        val session = SessionStore(requireContext())
        val token = session.getAccessToken()
        if (token == null) {
            // Not signed in - go to auth
            progress.visibility = View.GONE
            swipeRefresh.isRefreshing = false
            val intent = android.content.Intent(requireContext(), AuthActivity::class.java)
            startActivity(intent)
            return
        }
        progress.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        Thread {
            try {
                val items = loadItems(token)
                activity?.runOnUiThread {
                    progress.visibility = View.GONE
                    swipeRefresh.isRefreshing = false
                    adapter.submitList(items)
                    emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    progress.visibility = View.GONE
                    swipeRefresh.isRefreshing = false
                    val msg = e.message ?: ""
                    if (msg.contains("401") || msg.contains("unauthorized") || msg.contains("JWT")) {
                        // Session expired - clear and go to auth
                        SessionStore(requireContext()).clear()
                        val intent = android.content.Intent(requireContext(), AuthActivity::class.java)
                        startActivity(intent)
                    } else {
                        showEmpty()
                        Toast.makeText(requireContext(), "Could not load: $msg", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    private fun showEmpty() {
        progress.visibility = View.GONE
        swipeRefresh.isRefreshing = false
        adapter.submitList(emptyList())
        emptyView.visibility = View.VISIBLE
    }

    private fun createItem() {
        val session = SessionStore(requireContext())
        val token = session.getAccessToken()
        val userId = session.getUserId()
        if (token == null || userId == null) {
            Toast.makeText(requireContext(), "Please sign in first", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(requireContext(), "Creating...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val item = createNew(token, userId)
                activity?.runOnUiThread {
                    if (item != null) {
                        openEditor(item)
                    } else {
                        Toast.makeText(requireContext(), "Could not create", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "Could not create: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        // Refresh when returning from editor (in case title/content changed)
        if (::adapter.isInitialized) {
            refresh()
        }
    }
}

/** Documents tab - writer documents from Supabase. */
class DocumentListFragment : BaseListFragment() {
    override fun loadItems(token: String) = SupabaseClient.getDocuments(token)
    override fun createNew(token: String, userId: String): SupabaseClient.DocItem? {
        val id = SupabaseClient.createDocument(token, userId)
        return SupabaseClient.DocItem(id, "Untitled document", "", "", "doc")
    }
    override fun emptyText() = "No documents yet. Tap + to create one."
}

/** Notes tab - notes from Supabase. */
class NotesListFragment : BaseListFragment() {
    override fun loadItems(token: String) = SupabaseClient.getNotes(token)
    override fun createNew(token: String, userId: String): SupabaseClient.DocItem? {
        val id = SupabaseClient.createNote(token, userId)
        return SupabaseClient.DocItem(id, "Untitled", "", "", "note")
    }
    override fun emptyText() = "No notes yet. Tap + to create one."
}

/** PDFs tab - saved PDFs from Supabase. */
class PdfListFragment : BaseListFragment() {
    override fun loadItems(token: String) = SupabaseClient.getPdfs(token)
    override fun createNew(token: String, userId: String): SupabaseClient.DocItem? {
        // FAB opens the system file picker; the result is handled via activity result
        (activity as? MainActivity)?.openPdfPicker()
        return null // don't open an editor; the picker handles it
    }
    override fun emptyText() = "No saved PDFs yet. Tap + to open a PDF file."
}
