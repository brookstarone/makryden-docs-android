package com.makryden.docs

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.Layout
import android.text.Spannable
import android.text.TextPaint
import android.text.TextWatcher
import android.text.style.AbsoluteSizeSpan
import android.text.style.AlignmentSpan
import android.text.style.BulletSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.res.ResourcesCompat

/**
 * Native rich-text Writer editor. No WebView.
 * Formatting via Spannable spans; saved as HTML to Supabase for web compatibility.
 */
class WriterEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
    }

    private lateinit var titleEdit: EditText
    private lateinit var contentEdit: EditText
    private lateinit var toolbar: Toolbar
    private var docId: String = ""
    private var isNew: Boolean = false
    private var dirty = false

    // Bundled fonts (assets/writer/fonts/)
    private val fonts = listOf(
        "Roboto" to "writer/fonts/Roboto-400.woff2",
        "Roboto Bold" to "writer/fonts/Roboto-700.woff2",
        "Open Sans" to "writer/fonts/OpenSans-400.woff2",
        "Open Sans Bold" to "writer/fonts/OpenSans-700.woff2",
        "Lato" to "writer/fonts/Lato-400.woff2",
        "Lato Bold" to "writer/fonts/Lato-700.woff2",
        "Montserrat" to "writer/fonts/Montserrat-400.woff2",
        "Montserrat Bold" to "writer/fonts/Montserrat-700.woff2",
        "Poppins" to "writer/fonts/Poppins-400.woff2",
        "Poppins SemiBold" to "writer/fonts/Poppins-600.woff2",
        "Playfair Display" to "writer/fonts/PlayfairDisplay-400.woff2",
        "Playfair Display Bold" to "writer/fonts/PlayfairDisplay-700.woff2",
        "Merriweather" to "writer/fonts/Merriweather-400.woff2",
        "Merriweather Bold" to "writer/fonts/Merriweather-700.woff2"
    )
    private val typefaceCache = mutableMapOf<String, Typeface>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_writer_editor)

        docId = intent.getStringExtra(EXTRA_ID) ?: ""
        val initialTitle = intent.getStringExtra(EXTRA_TITLE) ?: "Untitled document"
        isNew = docId.isEmpty()

        toolbar = findViewById(R.id.toolbar)
        toolbar.title = "Document"
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        titleEdit = findViewById(R.id.doc_title)
        contentEdit = findViewById(R.id.doc_content)
        titleEdit.setText(initialTitle)

        setupFormatBar()

        val dirtyWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { dirty = true }
            override fun afterTextChanged(s: Editable?) {}
        }
        titleEdit.addTextChangedListener(dirtyWatcher)
        contentEdit.addTextChangedListener(dirtyWatcher)

        if (!isNew) {
            loadDocument()
        }
    }

    private fun setupFormatBar() {
        val bar: LinearLayout = findViewById(R.id.format_bar)
        val buttons = listOf(
            "B" to { toggleStyle(Typeface.BOLD) },
            "I" to { toggleStyle(Typeface.ITALIC) },
            "U" to { toggleSpan(UnderlineSpan::class.java) { UnderlineSpan() } },
            "S" to { toggleSpan(StrikethroughSpan::class.java) { StrikethroughSpan() } },
            "Font" to { showFontPicker() },
            "H1" to { applyHeading(32) },
            "H2" to { applyHeading(26) },
            "H3" to { applyHeading(22) },
            "Tx" to { applyHeading(0) }, // normal text
            "\u2022 List" to { toggleBullet() },
            "Center" to { applyAlignment(Layout.Alignment.ALIGN_CENTER) },
            "Left" to { applyAlignment(Layout.Alignment.ALIGN_NORMAL) },
            "Color" to { showColorPicker() },
            "Clear" to { clearFormatting() }
        )
        for ((label, action) in buttons) {
            val btn = androidx.appcompat.widget.AppCompatButton(this)
            btn.text = label
            btn.setTextColor(Color.parseColor("#E8C266"))
            btn.background = null
            btn.setPadding(24, 16, 24, 16)
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
        // If no selection, apply to current word
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
            .filter { it.style == style || (style == Typeface.BOLD && it.style == Typeface.BOLD_ITALIC) || (style == Typeface.ITALIC && it.style == Typeface.BOLD_ITALIC) }
        if (existing.isNotEmpty()) {
            for (span in existing) spannable.removeSpan(span)
        } else {
            spannable.setSpan(StyleSpan(style), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        dirty = true
    }

    private fun <T> toggleSpan(clazz: Class<T>, factory: () -> T) {
        val (start, end) = selection()
        if (start == end) return
        val spannable = contentEdit.text as Spannable
        @Suppress("UNCHECKED_CAST")
        val existing = spannable.getSpans(start, end, clazz as Class<Any>)
        if (existing.isNotEmpty()) {
            for (span in existing) spannable.removeSpan(span)
        } else {
            spannable.setSpan(factory() as Any, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        dirty = true
    }

    private fun applyHeading(sizeSp: Int) {
        val (start, end) = selection()
        val spannable = contentEdit.text as Spannable
        // Remove existing size spans in range
        for (span in spannable.getSpans(start, end, AbsoluteSizeSpan::class.java)) {
            spannable.removeSpan(span)
        }
        if (sizeSp > 0) {
            val px = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, sizeSp.toFloat(), resources.displayMetrics
            ).toInt()
            // Apply to the full line(s)
            val text = spannable.toString()
            var lineStart = start
            while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
            var lineEnd = end
            while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
            spannable.setSpan(AbsoluteSizeSpan(px), lineStart, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(StyleSpan(Typeface.BOLD), lineStart, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        dirty = true
    }

    private fun applyAlignment(alignment: Layout.Alignment) {
        val (start, end) = selection()
        val spannable = contentEdit.text as Spannable
        val text = spannable.toString()
        var lineStart = start
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        var lineEnd = end
        while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
        for (span in spannable.getSpans(lineStart, lineEnd, AlignmentSpan.Standard::class.java)) {
            spannable.removeSpan(span)
        }
        spannable.setSpan(
            AlignmentSpan.Standard(alignment),
            lineStart, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        dirty = true
    }

    private fun toggleBullet() {
        val (start, end) = selection()
        val spannable = contentEdit.text as Spannable
        val text = spannable.toString()
        var lineStart = start
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        var lineEnd = end
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

    private fun showFontPicker() {
        val names = fonts.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Font")
            .setItems(names) { _, which ->
                applyFont(fonts[which].second)
            }
            .show()
    }

    private fun applyFont(assetPath: String) {
        val (start, end) = selection()
        if (start == end) {
            Toast.makeText(this, "Select text first", Toast.LENGTH_SHORT).show()
            return
        }
        val typeface = typefaceCache.getOrPut(assetPath) {
            try {
                Typeface.createFromAsset(assets, assetPath)
            } catch (e: Exception) {
                Typeface.DEFAULT
            }
        }
        val spannable = contentEdit.text as Spannable
        // Remove existing custom typeface spans in range
        for (span in spannable.getSpans(start, end, CustomTypefaceSpan::class.java)) {
            spannable.removeSpan(span)
        }
        spannable.setSpan(
            CustomTypefaceSpan(typeface),
            start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        dirty = true
    }

    private fun showColorPicker() {
        val colors = intArrayOf(
            Color.BLACK, Color.DKGRAY, Color.GRAY,
            Color.RED, Color.parseColor("#C62828"),
            Color.parseColor("#E8C266"), // gold
            Color.parseColor("#2E7D32"), // green
            Color.BLUE, Color.parseColor("#1565C0"),
            Color.parseColor("#6A1B9A") // purple
        )
        val names = arrayOf("Black", "Dark gray", "Gray", "Red", "Dark red", "Gold", "Green", "Blue", "Dark blue", "Purple")
        AlertDialog.Builder(this)
            .setTitle("Text color")
            .setItems(names) { _, which ->
                val (start, end) = selection()
                if (start == end) {
                    Toast.makeText(this, "Select text first", Toast.LENGTH_SHORT).show()
                    return@setItems
                }
                val spannable = contentEdit.text as Spannable
                for (span in spannable.getSpans(start, end, ForegroundColorSpan::class.java)) {
                    spannable.removeSpan(span)
                }
                spannable.setSpan(
                    ForegroundColorSpan(colors[which]),
                    start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                dirty = true
            }
            .show()
    }

    private fun clearFormatting() {
        val (start, end) = selection()
        if (start == end) return
        val spannable = contentEdit.text as Spannable
        val spanTypes = listOf(
            StyleSpan::class.java,
            UnderlineSpan::class.java,
            StrikethroughSpan::class.java,
            ForegroundColorSpan::class.java,
            AbsoluteSizeSpan::class.java,
            BulletSpan::class.java,
            CustomTypefaceSpan::class.java
        )
        for (clazz in spanTypes) {
            @Suppress("UNCHECKED_CAST")
            for (span in spannable.getSpans(start, end, clazz as Class<Any>)) {
                spannable.removeSpan(span)
            }
        }
        dirty = true
    }

    private fun loadDocument() {
        val session = SessionStore(this)
        val token = session.getAccessToken() ?: return
        Thread {
            try {
                val (title, html) = SupabaseClient.getDocument(docId, token)
                runOnUiThread {
                    titleEdit.setText(title)
                    contentEdit.text = HtmlConverter.fromHtml(html)
                    dirty = false
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Could not load: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun saveDocument() {
        val session = SessionStore(this)
        val token = session.getAccessToken()
        val userId = session.getUserId()
        if (token == null) {
            Toast.makeText(this, "Please sign in first", Toast.LENGTH_SHORT).show()
            return
        }
        val title = titleEdit.text.toString().ifEmpty { "Untitled document" }
        val html = HtmlConverter.toHtml(contentEdit.text as Spannable)
        Toast.makeText(this, "Saving...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                if (isNew) {
                    if (userId == null) throw Exception("No user ID")
                    docId = SupabaseClient.createDocument(token, userId)
                    isNew = false
                }
                SupabaseClient.updateDocument(docId, token, title, html)
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

    private fun shareDocument() {
        val title = titleEdit.text.toString().ifEmpty { "Makryden Docs" }
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
                saveDocument()
                true
            }
            R.id.action_share -> {
                shareDocument()
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
                    saveDocument()
                    super.onBackPressed()
                }
                .setNegativeButton("Discard") { _, _ -> super.onBackPressed() }
                .setNeutralButton("Cancel", null)
                .show()
        } else {
            super.onBackPressed()
        }
    }

    /** Custom span for bundled fonts. */
    class CustomTypefaceSpan(private val typeface: Typeface) : TypefaceSpan("") {
        override fun updateDrawState(ds: TextPaint) {
            ds.typeface = typeface
        }
        override fun updateMeasureState(paint: TextPaint) {
            paint.typeface = typeface
        }
    }
}
