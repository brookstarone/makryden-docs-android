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
 * Native notes list. Standalone fragment using the notes-specific
 * NoteAdapter. Shows title, snippet, and date for each note.
 *
 * This is the canonical notes list for the native app. It replaces
 * the generic NotesListFragment/BaseListFragment path for notes.
 */
class NoteListFragment : Fragment() {

    private lateinit var adapter: NoteAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private lateinit var emptyView: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_note_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recycler = view.findViewById(R.id.recycler)
        swipeRefresh = view.findViewById(R.id.swipe_refresh)
        progress = view.findViewById(R.id.progress)
        emptyView = view.findViewById(R.id.empty_view)
        val fab: FloatingActionButton = view.findViewById(R.id.fab)

        adapter = NoteAdapter { item ->
            val intent = Intent(requireContext(), NoteEditorActivity::class.java).apply {
                putExtra(NoteEditorActivity.EXTRA_ID, item.id)
                putExtra(NoteEditorActivity.EXTRA_TITLE, item.title)
            }
            startActivity(intent)
        }
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        swipeRefresh.setOnRefreshListener { refresh() }
        fab.setOnClickListener { createNote() }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) {
            refresh()
        }
    }

    fun refresh() {
        val session = SessionStore(requireContext())
        val token = session.getAccessToken()
        if (token == null) {
            showEmpty("Sign in to see your notes.")
            return
        }
        progress.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        Thread {
            try {
                val items = SupabaseClient.getNotes(token)
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
                    showEmpty("Could not load notes.")
                    Toast.makeText(
                        requireContext(),
                        "Could not load: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }.start()
    }

    private fun showEmpty(message: String? = null) {
        progress.visibility = View.GONE
        swipeRefresh.isRefreshing = false
        if (::adapter.isInitialized) {
            adapter.submitList(emptyList())
        }
        if (message != null) {
            emptyView.text = message
        }
        emptyView.visibility = View.VISIBLE
    }

    private fun createNote() {
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
                val id = SupabaseClient.createNote(token, userId)
                activity?.runOnUiThread {
                    val intent = Intent(requireContext(), NoteEditorActivity::class.java).apply {
                        putExtra(NoteEditorActivity.EXTRA_ID, id)
                        putExtra(NoteEditorActivity.EXTRA_TITLE, "Untitled")
                    }
                    startActivity(intent)
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    Toast.makeText(
                        requireContext(),
                        "Could not create: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }.start()
    }
}
