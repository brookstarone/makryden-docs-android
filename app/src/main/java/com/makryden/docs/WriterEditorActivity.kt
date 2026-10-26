package com.makryden.docs

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.TextWatcher
import android.text.style.AbsoluteSizeSpan
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.BulletSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.LeadingMarginSpan
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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.res.ResourcesCompat
import com.makryden.docs.data.DocsRepository
import com.makryden.docs.sync.SyncScheduler

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
    private lateinit var wordCountView: TextView
    private var docId: String = ""
    private var isNew: Boolean = false
    private var dirty = false
    // Throttle word-count updates: running a full-text regex split on every
    // keystroke causes visible stutter on longer documents.
    private val wordCountHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val wordCountRunnable = Runnable { updateWordCount() }

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> insertImageFromUri(uri) }
        }
    }

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
        wordCountView = findViewById(R.id.word_count)
        titleEdit.setText(initialTitle)

        setupFormatBar()
        updateWordCount()

        val dirtyWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                dirty = true
                // Debounce: wait for a typing pause before recounting.
                wordCountHandler.removeCallbacks(wordCountRunnable)
                wordCountHandler.postDelayed(wordCountRunnable, 300)
            }
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
            "1. List" to { toggleNumberedList() },
            "Left" to { applyAlignment(Layout.Alignment.ALIGN_NORMAL) },
            "Center" to { applyAlignment(Layout.Alignment.ALIGN_CENTER) },
            "Right" to { applyAlignment(Layout.Alignment.ALIGN_OPPOSITE) },
            "Color" to { showColorPicker() },
            "HiLite" to { showHighlightPicker() },
            "Image" to { pickImage() },
            "Clear" to { clearFormatting() }
        )
        for ((label, action) in buttons) {
            val btn = androidx.appcompat.widget.AppCompatButton(this)
            btn.text = label
            btn.setTextColor(Color.parseColor("#E8C266"))
            btn.background = null
            btn.setPadding(24, 16, 24, 16)
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

    /** Toggle numbered list on the current line(s). */
    private fun toggleNumberedList() {
        val spannable = contentEdit.text as Spannable
        val text = spannable.toString()
        val (selStart, selEnd) = selection()
        // Find all line boundaries in the selection
        var pos = selStart
        while (pos > 0 && text[pos - 1] != '\n') pos--
        val lineStarts = mutableListOf<Int>()
        while (pos <= selEnd && pos < text.length) {
            lineStarts.add(pos)
            val nl = text.indexOf('\n', pos)
            if (nl == -1 || nl >= selEnd) break
            pos = nl + 1
        }
        if (lineStarts.isEmpty()) lineStarts.add(selStart)

        // Check if first line already has a number span
        val firstLineEnd = text.indexOf('\n', lineStarts[0]).let { if (it == -1) text.length else it }
        val existing = spannable.getSpans(lineStarts[0], firstLineEnd, NumberSpan::class.java)
        if (existing.isNotEmpty()) {
            // Remove all number spans in range
            val lastEnd = text.indexOf('\n', lineStarts.last()).let { if (it == -1) text.length else it }
            for (span in spannable.getSpans(lineStarts[0], lastEnd, NumberSpan::class.java)) {
                spannable.removeSpan(span)
            }
        } else {
            var num = 1
            for (ls in lineStarts) {
                val le = text.indexOf('\n', ls).let { if (it == -1) text.length else it }
                val margin = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 48f, resources.displayMetrics
                ).toInt()
                spannable.setSpan(
                    NumberSpan(num++, margin),
                    ls, le, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        dirty = true
    }

    /** Open gallery to pick an image for inline insertion. */
    private fun pickImage() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        intent.type = "image/*"
        imagePicker.launch(intent)
    }

    private fun insertImageFromUri(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            var bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (bitmap == null) {
                Toast.makeText(this, "Could not load image", Toast.LENGTH_SHORT).show()
                return
            }
            // Scale down to max 800px wide to keep things manageable
            val maxWidth = 800
            if (bitmap.width > maxWidth) {
                val ratio = maxWidth.toFloat() / bitmap.width
                val newHeight = (bitmap.height * ratio).toInt()
                bitmap = Bitmap.createScaledBitmap(bitmap, maxWidth, newHeight, true)
            }
            val drawable = BitmapDrawable(resources, bitmap)
            drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)

            val spannable = contentEdit.text as android.text.Editable
            val pos = contentEdit.selectionStart.coerceAtLeast(0)
            val placeholder = SpannableStringBuilder("\uFFFC\n")
            placeholder.setSpan(
                ImageSpan(drawable, ImageSpan.ALIGN_BASELINE),
                0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.insert(pos, placeholder)
            dirty = true
            Toast.makeText(this, "Image inserted", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not insert image", Toast.LENGTH_SHORT).show()
        }
    }

    /** Update the word count display. */
    private fun updateWordCount() {
        val text = contentEdit.text.toString().trim()
        val words = if (text.isEmpty()) 0 else text.split("\\s+".toRegex()).size
        val chars = contentEdit.text.length
        wordCountView.text = "$words words \u00B7 $chars characters"
    }

    /** Highlight color picker (background color). */
    private fun showHighlightPicker() {
        val colors = intArrayOf(
            Color.TRANSPARENT,
            Color.parseColor("#FFF59D"), // light yellow
            Color.parseColor("#A5D6A7"), // light green
            Color.parseColor("#90CAF9"), // light blue
            Color.parseColor("#CE93D8"), // light purple
            Color.parseColor("#FFAB91"), // light orange
            Color.parseColor("#E8C266")  // gold
        )
        val names = arrayOf("None", "Yellow", "Green", "Blue", "Purple", "Orange", "Gold")
        AlertDialog.Builder(this)
            .setTitle("Highlight color")
            .setItems(names) { _, which ->
                val (start, end) = selection()
                if (start == end) {
                    Toast.makeText(this, "Select text first", Toast.LENGTH_SHORT).show()
                    return@setItems
                }
                val spannable = contentEdit.text as Spannable
                for (span in spannable.getSpans(start, end, BackgroundColorSpan::class.java)) {
                    spannable.removeSpan(span)
                }
                if (colors[which] != Color.TRANSPARENT) {
                    spannable.setSpan(
                        BackgroundColorSpan(colors[which]),
                        start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                dirty = true
            }
            .show()
    }

    /** Export document as HTML via share sheet. */
    private fun exportHtml() {
        val title = titleEdit.text.toString().ifEmpty { "document" }
        val html = HtmlConverter.toHtml(contentEdit.text as Spannable)
        val fullHtml = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>" +
            title.replace("<", "&lt;").replace(">", "&gt;") +
            "</title></head><body>" + html + "</body></html>"
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/html"
        intent.putExtra(Intent.EXTRA_SUBJECT, "$title.html")
        intent.putExtra(Intent.EXTRA_TEXT, fullHtml)
        startActivity(Intent.createChooser(intent, "Export HTML via"))
    }

    private fun showFontPicker() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_font_picker, null)
        val recycler = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.font_list)
        recycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        recycler.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<FontViewHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): FontViewHolder {
                val tv = TextView(this@WriterEditorActivity)
                tv.setPadding(32, 24, 32, 24)
                tv.textSize = 18f
                tv.setTextColor(Color.WHITE)
                return FontViewHolder(tv)
            }
            override fun getItemCount() = fonts.size
            override fun onBindViewHolder(holder: FontViewHolder, position: Int) {
                val (name, path) = fonts[position]
                holder.textView.text = name
                try {
                    holder.textView.typeface = typefaceCache.getOrPut(path) {
                        Typeface.createFromAsset(assets, path)
                    }
                } catch (e: Exception) {
                    // keep default
                }
                holder.textView.setOnClickListener {
                    applyFont(path)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private class FontViewHolder(val textView: TextView) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(textView)

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
            BackgroundColorSpan::class.java,
            AbsoluteSizeSpan::class.java,
            BulletSpan::class.java,
            NumberSpan::class.java,
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
        Thread {
            // Token fetch happens here, on the background thread:
            // getValidAccessTokenBlocking() may refresh over the
            // network and must never run on the main thread.
            val token = try {
                session.getValidAccessTokenBlocking()
            } catch (e: Exception) {
                null
            } ?: return@Thread
            try {
                val (title, html) = SupabaseClient.getDocument(docId, token)
                runOnUiThread {
                    // Don't wipe text the user already typed while loading.
                    if (!dirty) {
                        titleEdit.setText(title)
                        contentEdit.text = HtmlConverter.fromHtml(html)
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

    private fun saveDocument() {
        val session = SessionStore(this)
        val title = titleEdit.text.toString().ifEmpty { "Untitled document" }
        val html = HtmlConverter.toHtml(contentEdit.text as Spannable)
        Toast.makeText(this, "Saving...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                // Token fetch happens here, on the background thread:
                // getValidAccessTokenBlocking() may refresh over the
                // network and must never run on the main thread.
                val token = try {
                    session.getValidAccessTokenBlocking()
                } catch (e: Exception) {
                    null
                }
                if (token == null) {
                    runOnUiThread {
                        Toast.makeText(this, "Please sign in first", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }
                val userId = try {
                    session.getUserId()
                } catch (e: Exception) {
                    null
                }
                if (isNew) {
                    if (userId == null) throw Exception("No user ID")
                    docId = SupabaseClient.createDocument(token, userId)
                    isNew = false
                }
                // A thrown update is treated like a false return: the
                // edit is queued, not lost. Create failures above still
                // reach the outer catch and keep today's toast.
                val synced = try {
                    SupabaseClient.updateDocument(docId, token, title, html)
                } catch (e: Exception) {
                    android.util.Log.w("MakrydenDocs", "updateDocument failed", e)
                    false
                }
                val repo = try {
                    DocsRepository(applicationContext)
                } catch (e: Exception) {
                    android.util.Log.w("MakrydenDocs", "DocsRepository failed", e)
                    null
                }
                if (synced) {
                    if (repo != null) {
                        try {
                            repo.cacheItem(SupabaseClient.DocItem(docId, title, "", "", "doc"), html)
                        } catch (e: Exception) {
                            android.util.Log.w("MakrydenDocs", "cacheItem failed", e)
                        }
                    }
                    runOnUiThread {
                        dirty = false
                        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    if (repo != null) {
                        try {
                            repo.queueUpdate("doc", docId, title, html)
                            repo.cacheItem(SupabaseClient.DocItem(docId, title, "", "", "doc"), html)
                        } catch (e: Exception) {
                            android.util.Log.w("MakrydenDocs", "queueUpdate failed", e)
                        }
                    }
                    runOnUiThread {
                        dirty = false
                        Toast.makeText(this, "Saved on this phone - will sync when you are back online", Toast.LENGTH_SHORT).show()
                    }
                    try {
                        SyncScheduler.enqueuePending(this)
                    } catch (e: Exception) {
                        android.util.Log.w("MakrydenDocs", "Sync enqueue failed", e)
                    }
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
            R.id.action_export_html -> {
                exportHtml()
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

    /**
     * Numbered-list span. Draws "N." in the leading margin.
     * Pure ASCII implementation.
     */
    class NumberSpan(private val number: Int, private val marginWidth: Int) : LeadingMarginSpan {
        override fun getLeadingMargin(first: Boolean): Int = marginWidth

        override fun drawLeadingMargin(
            c: Canvas, p: Paint, x: Int, dir: Int,
            top: Int, baseline: Int, bottom: Int,
            text: CharSequence, start: Int, end: Int,
            first: Boolean, layout: Layout?
        ) {
            if (!first) return
            val oldColor = p.color
            p.color = oldColor // keep text color
            val numberText = "$number."
            val width = p.measureText(numberText)
            c.drawText(numberText, (x + marginWidth - width - 16).toFloat(), baseline.toFloat(), p)
            p.color = oldColor
        }
    }
}
