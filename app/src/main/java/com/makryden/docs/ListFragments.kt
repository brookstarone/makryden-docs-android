package com.makryden.docs

import android.content.Intent
import android.os.Bundle
import android.util.Log
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

private const val TAG_LIST = "DocsList"

/**
 * Base fragment for native document/note/PDF lists.
 * Subclasses provide loadItems() and createNew().
 *
 * Hardened: every context/activity access is guarded so a detached
 * fragment can never crash the app (no requireContext(), no bare
 * activity?.runOnUiThread without an isAdded check).
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
        // view.context is safe here: the view is attached during onViewCreated
        recycler.layoutManager = LinearLayoutManager(view.context)
        recycler.adapter = adapter

        swipeRefresh.setOnRefreshListener { refresh() }
        fab.setOnClickListener { createItem() }

        refresh()
    }

    private fun openEditor(item: SupabaseClient.DocItem) {
        val ctx = context ?: run {
            Log.w(TAG_LIST, "openEditor dropped: fragment detached")
            return
        }
        val intent = when (item.kind) {
            "note" -> Intent(ctx, NoteEditorActivity::class.java).apply {
                putExtra(NoteEditorActivity.EXTRA_ID, item.id)
                putExtra(NoteEditorActivity.EXTRA_TITLE, item.title)
            }
            "pdf" -> Intent(ctx, PdfViewerActivity::class.java).apply {
                putExtra(PdfViewerActivity.EXTRA_ID, item.id)
                putExtra(PdfViewerActivity.EXTRA_TITLE, item.title)
            }
            else -> Intent(ctx, WriterEditorActivity::class.java).apply {
                putExtra(WriterEditorActivity.EXTRA_ID, item.id)
                putExtra(WriterEditorActivity.EXTRA_TITLE, item.title)
            }
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG_LIST, "openEditor failed", e)
        }
    }

    fun refresh() {
        // Guard: fragment may be attached but views not yet created (race condition)
        if (!::progress.isInitialized || view == null) {
            android.util.Log.w("BaseListFragment", "refresh dropped: views not initialized yet")
            return
        }
        val ctx = context
        if (ctx == null || !isAdded) {
            Log.w(TAG_LIST, "refresh dropped: fragment not attached")
            return
        }
        val session = try {
            SessionStore(ctx)
        } catch (e: Exception) {
            Log.w(TAG_LIST, "SessionStore failed", e)
            return
        }
        val token = try {
            session.getAccessToken()
        } catch (e: Exception) {
            Log.w(TAG_LIST, "getAccessToken failed", e)
            null
        }
        if (token == null) {
            // Not signed in - show empty. MainActivity owns the auth gate.
            progress.visibility = View.GONE
            swipeRefresh.isRefreshing = false
            showEmpty()
            return
        }
        progress.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        Thread {
            try {
                val items = loadItems(token)
                val act = activity
                if (act == null || !isAdded) {
                    Log.w(TAG_LIST, "Fragment detached before list load finished; dropping result")
                    return@Thread
                }
                act.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    try {
                        progress.visibility = View.GONE
                        swipeRefresh.isRefreshing = false
                        adapter.submitList(items)
                        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                    } catch (e: Exception) {
                        Log.w(TAG_LIST, "List UI update failed", e)
                    }
                }
            } catch (e: Exception) {
                val act = activity
                if (act == null || !isAdded) {
                    Log.w(TAG_LIST, "Fragment detached during list load error; dropping", e)
                    return@Thread
                }
                act.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    progress.visibility = View.GONE
                    swipeRefresh.isRefreshing = false
                    val msg = e.message ?: ""
                    val c = context
                    if (msg.contains("401") || msg.contains("unauthorized") || msg.contains("JWT")) {
                        // Session expired - clear and go to auth
                        try {
                            session.clear()
                        } catch (_: Exception) {
                        }
                        if (c != null) {
                            try {
                                startActivity(Intent(c, AuthActivity::class.java))
                            } catch (_: Exception) {
                            }
                        }
                    } else {
                        showEmpty()
                        if (c != null) {
                            try {
                                Toast.makeText(c, "Could not load: $msg", Toast.LENGTH_LONG).show()
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            }
        }.start()
    }

    private fun showEmpty() {
        // Called only from the UI thread while attached
        try {
            progress.visibility = View.GONE
            swipeRefresh.isRefreshing = false
            adapter.submitList(emptyList())
            emptyView.visibility = View.VISIBLE
        } catch (e: Exception) {
            Log.w(TAG_LIST, "showEmpty failed", e)
        }
    }

    private fun createItem() {
        val ctx = context ?: run {
            Log.w(TAG_LIST, "createItem dropped: fragment detached")
            return
        }
        val session = try {
            SessionStore(ctx)
        } catch (e: Exception) {
            Log.w(TAG_LIST, "SessionStore failed", e)
            return
        }
        val token = try {
            session.getAccessToken()
        } catch (e: Exception) {
            null
        }
        val userId = try {
            session.getUserId()
        } catch (e: Exception) {
            null
        }
        if (token == null || userId == null) {
            try {
                Toast.makeText(ctx, "Please sign in first", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.w(TAG_LIST, "Toast failed", e)
            }
            return
        }
        try {
            Toast.makeText(ctx, "Creating...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.w(TAG_LIST, "Toast failed", e)
        }
        Thread {
            try {
                val item = createNew(token, userId)
                val act = activity
                if (act == null || !isAdded) {
                    Log.w(TAG_LIST, "Fragment detached before create finished; dropping result")
                    return@Thread
                }
                act.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    val c = context
                    if (item != null) {
                        openEditor(item)
                    } else if (c != null) {
                        try {
                            Toast.makeText(c, "Could not create", Toast.LENGTH_SHORT).show()
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (e: Exception) {
                val act = activity
                if (act == null || !isAdded) {
                    Log.w(TAG_LIST, "Fragment detached during create error; dropping", e)
                    return@Thread
                }
                act.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    val c = context
                    if (c != null) {
                        try {
                            Toast.makeText(c, "Could not create: ${e.message}", Toast.LENGTH_SHORT).show()
                        } catch (_: Exception) {
                        }
                    }
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
