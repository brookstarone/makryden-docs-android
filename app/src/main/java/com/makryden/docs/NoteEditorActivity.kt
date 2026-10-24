package com.makryden.docs

import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.BulletSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

/**
 * Native note editor. Title + content with basic formatting
 * (bold, italic, bullets, checklists). No WebView.
 */
class NoteEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
    }

    private lateinit var titleEdit: EditText
    private lateinit var contentEdit: EditText
    private var noteId: String = ""
    private var isNew: Boolean = false
    private var dirty = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_note_editor)

        noteId = intent.getStringExtra(EXTRA_ID) ?: ""
        val initialTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""
        isNew = noteId.isEmpty()

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        toolbar.title = "Note"
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        titleEdit = findViewById(R.id.note_title)
        contentEdit = findViewById(R.id.note_content)
        // "Untitled" placeholder from list -> empty for editing
        titleEdit.setText(if (initialTitle == "Untitled") "" else initialTitle)

        setupFormatBar()

        val dirtyWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { dirty = true }
            override fun afterTextChanged(s: Editable?) {}
        }
        titleEdit.addTextChangedListener(dirtyWatcher)
        contentEdit.addTextChangedListener(dirtyWatcher)

        // Check for shared text from another app
        if (isNew) {
            val shared = getSharedPreferences("mk_share", MODE_PRIVATE)
                .getString("shared_text", null)
            if (shared != null) {
                contentEdit.setText(shared)
                getSharedPreferences("mk_share", MODE_PRIVATE).edit()
                    .remove("shared_text").apply()
            }
        } else {
            loadNote()
        }
    }

    private fun setupFormatBar() {
        val bar: LinearLayout = findViewById(R.id.format_bar)
        val buttons = listOf(
            "B" to { toggleStyle(Typeface.BOLD) },
            "I" to { toggleStyle(Typeface.ITALIC) },
            "U" to { toggleUnderline() },
            "S" to { toggleStrikethrough() },
            "\u2022" to { toggleBullet() },
            "1." to { toggleNumberedList() },
            "\u2610" to { toggleChecklist() },
            "Clear" to { clearFormatting() }
        )
        for ((label, action) in buttons) {
            val btn = androidx.appcompat.widget.AppCompatButton(this)
            btn.text = label
            btn.setTextColor(android.graphics.Color.parseColor("#E8C266"))
            btn.background = null
            btn.setPadding(28, 16, 28, 16)
            btn.textSize = 18f
            // Never steal focus from the EditText: tapping a format button
            // must not dismiss the keyboard or move the cursor.
            btn.isFocusable = false
            btn.isFocusableInTouchMode = false
            btn.setOnClickListener { action() }
            bar.addView(btn)
        }
    }

    private fun selection(): Pair<Int, Int> {
        var start = contentEdit.selectionStart
        var end = contentEdit.selectionEnd
        if (start > end) { val t = start; start = end; end = t }
        if (start < 0) start = 0
        if (end < 0) end = 0
        if (start == end) {
            val text = contentEdit.text.toString()
            var s = start
            var e = end
            while (s > 0 && !text[s - 1].isWhitespace()) s--
            while (e < text.length && !text[e].isWhitespace()) e++
            return Pair(s, e)
        }
        return Pair(start, end)
    }

    private fun toggleStyle(style: Int) {
        val (start, end) = selection()
        if (start == end) return
        val spannable = contentEdit.text as Spannable
        val existing = spannable.getSpans(start, end, StyleSpan::class.java)
            .filter { it.style == style }
        if (existing.isNotEmpty()) {
            for (span in existing) spannable.removeSpan(span)
        } else {
            spannable.setSpan(StyleSpan(style), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        dirty = true
    }

    private fun toggleBullet() {
        val spannable = contentEdit.text as Spannable
        val text = spannable.toString()
        val pos = contentEdit.selectionStart.coerceAtLeast(0)
        var lineStart = pos
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        var lineEnd = pos
        while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
        val existing = spannable.getSpans(lineStart, lineEnd, BulletSpan::class.java)
        if (existing.isNotEmpty()) {
            for (span in existing) spannable.removeSpan(span)
        } else {
            val gap = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics
            ).toInt()
            spannable.setSpan(BulletSpan(gap), lineStart, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        dirty = true
    }

    private fun toggleChecklist() {
        // Checklist = strikethrough toggle on the line (checked = done)
        val spannable = contentEdit.text as android.text.Editable
        val text = spannable.toString()
        val pos = contentEdit.selectionStart.coerceAtLeast(0)
        var lineStart = pos
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        var lineEnd = pos
        while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
        // Ensure the line starts with a checkbox marker
        val line = text.substring(lineStart, lineEnd)
        if (!line.startsWith("\u2610 ") && !line.startsWith("\u2611 ")) {
            spannable.insert(lineStart, "\u2610 ")
            lineEnd += 2
        }
        // Toggle checked state
        val marker = spannable.substring(lineStart, lineStart + 1)
        if (marker == "\u2610") {
            spannable.replace(lineStart, lineStart + 1, "\u2611")
            spannable.setSpan(
                StrikethroughSpan(), lineStart + 2, lineEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        } else {
            spannable.replace(lineStart, lineStart + 1, "\u2610")
            for (span in spannable.getSpans(lineStart, lineEnd, StrikethroughSpan::class.java)) {
                spannable.removeSpan(span)
            }
        }
        dirty = true
    }

    private fun toggleUnderline() {
        val (start, end) = selection()
        if (start == end) return
        val spannable = contentEdit.text as Spannable
        val existing = spannable.getSpans(start, end, UnderlineSpan::class.java)
        if (existing.isNotEmpty()) {
            for (span in existing) spannable.removeSpan(span)
        } else {
            spannable.setSpan(UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        dirty = true
    }

    private fun toggleStrikethrough() {
        val (start, end) = selection()
        if (start == end) return
        val spannable = contentEdit.text as Spannable
        val existing = spannable.getSpans(start, end, StrikethroughSpan::class.java)
        if (existing.isNotEmpty()) {
            for (span in existing) spannable.removeSpan(span)
        } else {
            spannable.setSpan(StrikethroughSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        dirty = true
    }

    private fun toggleNumberedList() {
        val spannable = contentEdit.text as android.text.Editable
        val text = spannable.toString()
        val pos = contentEdit.selectionStart.coerceAtLeast(0)
        var lineStart = pos
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        var lineEnd = pos
        while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
        val line = text.substring(lineStart, lineEnd)
        val numbered = Regex("^\\d+\\. ")
        val match = numbered.find(line)
        if (match != null) {
            spannable.delete(lineStart, lineStart + match.value.length)
        } else {
            spannable.insert(lineStart, "1. ")
        }
        dirty = true
    }

    private fun clearFormatting() {
        val (start, end) = selection()
        if (start == end) return
        val spannable = contentEdit.text as Spannable
        for (span in spannable.getSpans(start, end, StyleSpan::class.java)) spannable.removeSpan(span)
        for (span in spannable.getSpans(start, end, BulletSpan::class.java)) spannable.removeSpan(span)
        for (span in spannable.getSpans(start, end, StrikethroughSpan::class.java)) spannable.removeSpan(span)
        for (span in spannable.getSpans(start, end, UnderlineSpan::class.java)) spannable.removeSpan(span)
        dirty = true
    }

    private fun loadNote() {
        val token = SessionStore(this).getAccessToken() ?: return
        Thread {
            try {
                val (title, text, _) = SupabaseClient.getNote(noteId, token)
                runOnUiThread {
                    // Don't wipe text the user already typed while loading.
                    if (!dirty) {
                        titleEdit.setText(title)
                        contentEdit.setText(text)
                        dirty = false
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Could not load: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun saveNote() {
        val session = SessionStore(this)
        val token = session.getAccessToken()
        val userId = session.getUserId()
        if (token == null) {
            Toast.makeText(this, "Please sign in first", Toast.LENGTH_SHORT).show()
            return
        }
        val title = titleEdit.text.toString()
        val text = contentEdit.text.toString()
        Toast.makeText(this, "Saving...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                if (isNew) {
                    if (userId == null) throw Exception("No user ID")
                    noteId = SupabaseClient.createNote(token, userId)
                    isNew = false
                }
                SupabaseClient.updateNote(noteId, token, title, text)
                runOnUiThread {
                    dirty = false
                    Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun shareNote() {
        val title = titleEdit.text.toString().ifEmpty { "Note" }
        val text = contentEdit.text.toString()
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND)
        intent.type = "text/plain"
        intent.putExtra(android.content.Intent.EXTRA_SUBJECT, title)
        intent.putExtra(android.content.Intent.EXTRA_TEXT, text)
        startActivity(android.content.Intent.createChooser(intent, "Share via"))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.writer_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                true
            }
            R.id.action_save -> {
                saveNote()
                true
            }
            R.id.action_share -> {
                shareNote()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onBackPressed() {
        if (dirty) {
            AlertDialog.Builder(this)
                .setTitle("Unsaved changes")
                .setMessage("Save before leaving?")
                .setPositiveButton("Save") { _, _ ->
                    saveNote()
                    super.onBackPressed()
                }
                .setNegativeButton("Discard") { _, _ -> super.onBackPressed() }
                .setNeutralButton("Cancel", null)
                .show()
        } else {
            super.onBackPressed()
        }
    }
}
