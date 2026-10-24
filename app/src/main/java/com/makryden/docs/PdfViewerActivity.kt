package com.makryden.docs

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File
import java.io.FileOutputStream

/**
 * Native PDF viewer: PdfRenderer + pinch-to-zoom pages + annotation tools.
 * No WebView. Annotations persist as JSON in app files AND can be burned
 * into a real exported PDF via PdfExporter (Share auto-exports when
 * annotations exist; menu offers "Export annotated PDF").
 */
class PdfViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_URI = "uri"
    }

    private lateinit var viewPager: ViewPager2
    private lateinit var pageIndicator: TextView
    private lateinit var progress: ProgressBar
    private lateinit var toolbar: Toolbar
    private lateinit var annotBar: LinearLayout
    private lateinit var colorRow: LinearLayout

    private var renderer: PdfRenderer? = null
    private var parcelFd: ParcelFileDescriptor? = null
    private var pageCount = 0
    private var pdfFile: File? = null
    private var docId: String = ""
    private var uriString: String? = null
    // Stable key for the annotation sidecar file. Computed ONCE in onCreate:
    // previously annotFile() used System.currentTimeMillis() for URI PDFs,
    // so load and save used different files and signatures vanished on reopen.
    private var annotKey: String = "unknown"

    private var currentTool: PdfAnnotations.Tool = PdfAnnotations.Tool.NONE
    private var currentColor: Int = Color.parseColor("#E8C266")

    // All annotations across pages
    private val allStrokes = mutableListOf<PdfAnnotations.Stroke>()

    // AcroForm fields across pages (extracted on a background thread).
    private var allFormFields: List<PdfFormFields.FormField> = emptyList()
    private var formModeOn = false
    private var hasXfa = false
    // Master map of user-entered form values (name -> value). Overlay
    // instances are recycled by the pager, so values must live here, not
    // only in the per-page overlay.
    private val masterFieldValues = mutableMapOf<String, String>()

    private val toolButtons = mutableMapOf<PdfAnnotations.Tool, ImageButton>()
    private val annotColors = intArrayOf(
        0xFFE8C266.toInt(), // gold
        0xFFFF5252.toInt(), // red
        0xFF69F0AE.toInt(), // green
        0xFF40C4FF.toInt(), // blue
        0xFFFFFFFF.toInt(), // white
        0xFF000000.toInt()  // black
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_viewer)
        // Required before any PDFBox call (font loading fails without it).
        try { PDFBoxResourceLoader.init(applicationContext) } catch (e: Exception) { }

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "PDF"
        docId = intent.getStringExtra(EXTRA_ID) ?: ""
        val uriString = intent.getStringExtra(EXTRA_URI)
        this.uriString = uriString
        annotKey = computeAnnotKey()

        toolbar = findViewById(R.id.toolbar)
        toolbar.title = title
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        viewPager = findViewById(R.id.view_pager)
        pageIndicator = findViewById(R.id.page_indicator)
        progress = findViewById(R.id.progress)
        annotBar = findViewById(R.id.annot_bar)
        colorRow = findViewById(R.id.color_row)

        buildToolButtons()
        buildColorRow()

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                pageIndicator.text = "${position + 1} / $pageCount"
            }
        })

        loadAnnotations()

        progress.visibility = View.VISIBLE
        Thread {
            try {
                pdfFile = when {
                    uriString != null -> copyUriToTemp(Uri.parse(uriString))
                    docId.isNotEmpty() -> downloadPdf(docId)
                    else -> null
                }
                if (pdfFile == null) throw Exception("No PDF source")
                openRenderer(pdfFile!!)
                try {
                    allFormFields = PdfFormFields.extract(pdfFile!!)
                    hasXfa = PdfFormFields.hasXfa(pdfFile!!)
                } catch (e: Exception) { }
                runOnUiThread {
                    progress.visibility = View.GONE
                    pageCount = renderer?.pageCount ?: 0
                    viewPager.adapter = PdfPageAdapter(this)
                    pageIndicator.text = if (pageCount > 0) "1 / $pageCount" else "0 / 0"
                    invalidateOptionsMenu()
                    if (allFormFields.isEmpty() && hasXfa) {
                        Toast.makeText(
                            this,
                            "This form can't be filled in-app",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    Toast.makeText(this, "Could not open PDF: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }

    // ---------- Toolbar construction ----------

    private fun buildToolButtons() {
        val tools = listOf(
            Triple(PdfAnnotations.Tool.HIGHLIGHT, R.drawable.ic_highlight, "Highlight"),
            Triple(PdfAnnotations.Tool.UNDERLINE, R.drawable.ic_underline, "Underline"),
            Triple(PdfAnnotations.Tool.TEXT_NOTE, R.drawable.ic_text_note, "Text note"),
            Triple(PdfAnnotations.Tool.RECTANGLE, R.drawable.ic_rect, "Rectangle"),
            Triple(PdfAnnotations.Tool.FREEHAND, R.drawable.ic_pen, "Draw"),
            Triple(PdfAnnotations.Tool.SIGNATURE, R.drawable.ic_signature, "Signature")
        )
        for ((tool, iconRes, desc) in tools) {
            val btn = ImageButton(this).apply {
                setImageResource(iconRes)
                contentDescription = desc
                setBackgroundResource(android.R.drawable.btn_default_small)
                val pad = (10 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
                setOnClickListener { selectTool(tool) }
            }
            val lp = LinearLayout.LayoutParams(
                (52 * resources.displayMetrics.density).toInt(),
                (52 * resources.displayMetrics.density).toInt()
            )
            lp.setMargins(4, 4, 4, 4)
            annotBar.addView(btn, lp)
            toolButtons[tool] = btn
        }
        updateToolButtonStates()
    }

    private fun buildColorRow() {
        for (c in annotColors) {
            val swatch = View(this).apply {
                setBackgroundColor(c)
                setOnClickListener {
                    currentColor = c
                    updateColorRow()
                    applyToVisibleOverlays()
                }
            }
            val size = (36 * resources.displayMetrics.density).toInt()
            val lp = LinearLayout.LayoutParams(size, size)
            lp.setMargins(8, 8, 8, 8)
            colorRow.addView(swatch, lp)
            swatch.tag = c
        }
        updateColorRow()
    }

    private fun updateColorRow() {
        for (i in 0 until colorRow.childCount) {
            val v = colorRow.getChildAt(i)
            val selected = (v.tag as? Int) == currentColor
            v.alpha = if (selected) 1f else 0.4f
            v.scaleX = if (selected) 1.2f else 1f
            v.scaleY = if (selected) 1.2f else 1f
        }
    }

    private fun selectTool(tool: PdfAnnotations.Tool) {
        currentTool = if (currentTool == tool) PdfAnnotations.Tool.NONE else tool
        updateToolButtonStates()
        applyToVisibleOverlays()
        val label = when (currentTool) {
            PdfAnnotations.Tool.NONE -> "Annotation off"
            PdfAnnotations.Tool.HIGHLIGHT -> "Highlight: drag on the page"
            PdfAnnotations.Tool.UNDERLINE -> "Underline: drag on the page"
            PdfAnnotations.Tool.TEXT_NOTE -> "Text note: tap the page"
            PdfAnnotations.Tool.RECTANGLE -> "Rectangle: drag on the page"
            PdfAnnotations.Tool.FREEHAND -> "Draw: trace on the page"
            PdfAnnotations.Tool.SIGNATURE -> "Signature: sign on the page"
        }
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
    }

    private fun updateToolButtonStates() {
        for ((tool, btn) in toolButtons) {
            btn.alpha = if (tool == currentTool) 1f else 0.5f
        }
    }

    private fun applyToVisibleOverlays() {
        val adapter = viewPager.adapter as? PdfPageAdapter ?: return
        adapter.forEachOverlay { overlay ->
            overlay.tool = currentTool
            overlay.paintColor = currentColor
        }
    }

    // ---------- Annotations persistence ----------

    /**
     * Stable sidecar key. Account PDFs use their docId (unchanged, so
     * existing saved annotations keep loading). URI/shared PDFs use a
     * SHA-256 hash of the URI string, so the SAME pdf maps to the SAME
     * annotation file every time it is opened.
     */
    private fun computeAnnotKey(): String {
        if (docId.isNotEmpty()) return docId
        val uri = uriString
        if (uri != null) {
            return try {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val hash = digest.digest(uri.toByteArray(Charsets.UTF_8))
                val hex = StringBuilder()
                for (i in 0 until 16) {
                    hex.append(String.format("%02x", hash[i]))
                }
                "uri_$hex"
            } catch (e: Exception) {
                "uri_" + uri.hashCode().toString().replace("-", "n")
            }
        }
        return "unknown"
    }

    private fun annotFile(): File {
        return File(filesDir, "pdf_annot_$annotKey.json")
    }

    private fun loadAnnotations() {
        try {
            val f = annotFile()
            if (f.exists()) {
                allStrokes.clear()
                allStrokes.addAll(PdfAnnotations.fromJson(f.readText()))
            }
        } catch (e: Exception) { }
    }

    private fun saveAnnotations() {
        try {
            annotFile().writeText(PdfAnnotations.toJson(allStrokes))
        } catch (e: Exception) { }
    }

    private fun strokesForPage(page: Int): MutableList<PdfAnnotations.Stroke> {
        return allStrokes.filter { it.pageIndex == page }.toMutableList()
    }

    private fun onOverlayStrokeAdded(page: Int, overlay: PdfAnnotationView) {
        // Sync overlay strokes back to master list for this page
        allStrokes.removeAll { it.pageIndex == page }
        for (s in overlay.strokes) {
            allStrokes.add(s.copy(pageIndex = page))
        }
        saveAnnotations()
    }

    // ---------- PDF loading ----------

    private fun copyUriToTemp(uri: Uri): File {
        val outFile = File(cacheDir, "shared_${System.currentTimeMillis()}.pdf")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
        return outFile
    }

    private fun downloadPdf(id: String): File {
        val session = SessionStore(this)
        val token = session.getAccessToken() ?: throw Exception("Not signed in")
        val anonKey = getAnonKey()

        val url = java.net.URL(
            "https://bamrgsjgvakyucyqtwlg.supabase.co/rest/v1/pdf_documents" +
            "?select=storage_path&id=eq.$id&limit=1"
        )
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.setRequestProperty("apikey", anonKey)
        conn.setRequestProperty("Authorization", "Bearer $token")
        val reader = java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream))
        val json = reader.readText()
        reader.close()
        val arr = org.json.JSONArray(json)
        if (arr.length() == 0) throw Exception("PDF not found")
        val storagePath = arr.getJSONObject(0).getString("storage_path")

        val dlUrl = java.net.URL(
            "https://bamrgsjgvakyucyqtwlg.supabase.co/storage/v1/object/user-pdfs/$storagePath"
        )
        val dlConn = dlUrl.openConnection() as java.net.HttpURLConnection
        dlConn.setRequestProperty("apikey", anonKey)
        dlConn.setRequestProperty("Authorization", "Bearer $token")
        val outFile = File(cacheDir, "pdf_$id.pdf")
        dlConn.inputStream.use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
        return outFile
    }

    private fun getAnonKey(): String {
        // Same public anon key as SupabaseClient (designed to be public)
        return "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImJhbXJnc2pndmFreXVjeXF0d2xnIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODExMjUwMzAsImV4cCI6MjA5NjcwMTAzMH0.4rW9kVK1E-YFvjBvQd-VTPh5pd_O7ZTzYAx_BkyPbH0"
    }

    private fun openRenderer(file: File) {
        parcelFd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = PdfRenderer(parcelFd!!)
    }

    fun renderPage(index: Int, width: Int): Bitmap? {
        val r = renderer ?: return null
        if (index < 0 || index >= r.pageCount) return null
        return synchronized(r) {
            val page = r.openPage(index)
            try {
                val scale = width.toFloat() / page.width
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            } finally {
                page.close()
            }
        }
    }

    private fun closeRenderer() {
        try { renderer?.close() } catch (e: Exception) { }
        try { parcelFd?.close() } catch (e: Exception) { }
        renderer = null
        parcelFd = null
    }

    // ---------- Form filling ----------

    private fun toggleFormMode() {
        formModeOn = !formModeOn
        (viewPager.adapter as? PdfPageAdapter)?.setFormMode(formModeOn)
        invalidateOptionsMenu()
        Toast.makeText(
            this,
            if (formModeOn) "Fill form: tap a field" else "Fill form off",
            Toast.LENGTH_SHORT
        ).show()
    }

    /** Short display name: last segment, without [0] indices. */
    private fun fieldDisplayName(field: PdfFormFields.FormField): String {
        val last = field.name.substringAfterLast('.')
        return last.replace(Regex("\\[\\d+\\]"), "").ifEmpty { field.name }
    }

    private fun showFieldEditDialog(field: PdfFormFields.FormField, overlay: PdfFormOverlayView) {
        val input = EditText(this).apply {
            setText(overlay.fieldValues[field.name] ?: field.currentValue)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        AlertDialog.Builder(this)
            .setTitle(fieldDisplayName(field))
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                overlay.setFieldValue(field.name, input.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFieldChoiceDialog(field: PdfFormFields.FormField, overlay: PdfFormOverlayView) {
        val opts = field.options.toTypedArray()
        if (opts.isEmpty()) return
        val current = overlay.fieldValues[field.name] ?: field.currentValue
        val checked = opts.indexOf(current)
        AlertDialog.Builder(this)
            .setTitle(fieldDisplayName(field))
            .setSingleChoiceItems(opts, checked) { dialog, which ->
                overlay.setFieldValue(field.name, opts[which])
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Save user-entered values back into a copy of the PDF and reopen it. */
    private fun saveFilledForm() {
        val file = pdfFile ?: return
        if (masterFieldValues.isEmpty()) {
            Toast.makeText(this, "No changes to save", Toast.LENGTH_SHORT).show()
            return
        }
        val values = masterFieldValues.toMap()
        progress.visibility = View.VISIBLE
        Thread {
            try {
                val out = File(cacheDir, "filled_${System.currentTimeMillis()}.pdf")
                val saved = PdfFormFields.saveFilled(file, values, out)
                runOnUiThread {
                    progress.visibility = View.GONE
                    if (saved != null) {
                        closeRenderer()
                        pdfFile = saved
                        openRenderer(saved)
                        try { allFormFields = PdfFormFields.extract(saved) } catch (e: Exception) { }
                        masterFieldValues.clear()
                        pageCount = renderer?.pageCount ?: 0
                        viewPager.adapter = PdfPageAdapter(this)
                        pageIndicator.text = if (pageCount > 0) "1 / $pageCount" else "0 / 0"
                        invalidateOptionsMenu()
                        Toast.makeText(this, "Filled form saved", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ---------- Menu ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.pdf_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val hasFields = allFormFields.isNotEmpty()
        menu.findItem(R.id.action_fill_form)?.isVisible = hasFields
        menu.findItem(R.id.action_fill_form)?.isChecked = formModeOn
        menu.findItem(R.id.action_save_filled)?.isVisible = hasFields
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { onBackPressed(); true }
            R.id.action_highlight -> { selectTool(PdfAnnotations.Tool.HIGHLIGHT); true }
            R.id.action_share -> { sharePdf(); true }
            R.id.action_export -> { exportAnnotatedCopy(); true }
            R.id.action_fill_form -> { toggleFormMode(); true }
            R.id.action_save_filled -> { saveFilledForm(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun sharePdf() {
        val file = pdfFile ?: return
        if (allStrokes.isEmpty()) {
            shareFile(file)
            return
        }
        // Annotations exist: export a copy with them burned in, then share it.
        progress.visibility = View.VISIBLE
        Thread {
            try {
                val out = File(cacheDir, "annotated_share_${System.currentTimeMillis()}.pdf")
                PdfExporter.export(file, allStrokes.toList(), out)
                runOnUiThread {
                    progress.visibility = View.GONE
                    shareFile(out)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    Toast.makeText(
                        this,
                        "Export failed (${e.message}); sharing original instead",
                        Toast.LENGTH_LONG
                    ).show()
                    shareFile(file)
                }
            }
        }.start()
    }

    /** Exports the annotated PDF into app files and offers it for sharing. */
    private fun exportAnnotatedCopy() {
        val file = pdfFile ?: return
        if (allStrokes.isEmpty()) {
            Toast.makeText(this, "No annotations yet - nothing to export", Toast.LENGTH_SHORT).show()
            return
        }
        progress.visibility = View.VISIBLE
        Thread {
            try {
                val base = (toolbar.title?.toString() ?: "document")
                    .replace(Regex("[^A-Za-z0-9._-]+"), "_")
                    .take(60)
                    .ifEmpty { "document" }
                val out = File(filesDir, base + "_annotated.pdf")
                PdfExporter.export(file, allStrokes.toList(), out)
                runOnUiThread {
                    progress.visibility = View.GONE
                    Toast.makeText(
                        this,
                        "Annotated PDF saved: $base" + "_annotated.pdf",
                        Toast.LENGTH_LONG
                    ).show()
                    shareFile(out)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun shareFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share PDF via"))
        } catch (e: Exception) {
            Toast.makeText(this, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        super.onPause()
        saveAnnotations()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { renderer?.close(); parcelFd?.close() } catch (e: Exception) { }
    }

    // ---------- Text note dialog ----------

    private fun showTextNoteDialog(nx: Float, ny: Float, overlay: PdfAnnotationView) {
        val input = EditText(this).apply {
            hint = "Note text"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        AlertDialog.Builder(this)
            .setTitle("Text note")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val txt = input.text.toString()
                if (txt.isNotBlank()) {
                    overlay.addTextNote(nx, ny, txt)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Adapter ----------

    inner class PageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ZoomableImageView = view.findViewById(R.id.page_image)
        val overlay: PdfAnnotationView = view.findViewById(R.id.annotation_overlay)
        val formOverlay: PdfFormOverlayView = view.findViewById(R.id.form_overlay)
        var boundPosition: Int = -1
    }

    inner class PdfPageAdapter(private val ctx: Context) :
        RecyclerView.Adapter<PageViewHolder>() {

        private val overlays = mutableMapOf<Int, PdfAnnotationView>()
        private val formOverlays = mutableMapOf<Int, PdfFormOverlayView>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
            val view = LayoutInflater.from(ctx).inflate(R.layout.item_pdf_page, parent, false)
            return PageViewHolder(view)
        }

        override fun getItemCount(): Int = pageCount

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            holder.boundPosition = position
            holder.imageView.setImageDrawable(null)
            Thread {
                val width = 1080
                val bitmap = renderPage(position, width)
                holder.imageView.post {
                    // Guard: holder may have been recycled to another page
                    // while rendering. Never paint a stale bitmap over it.
                    if (bitmap != null && holder.boundPosition == position) {
                        holder.imageView.setImageBitmap(bitmap)
                    }
                }
            }.start()

            holder.overlay.syncWith(holder.imageView)
            holder.overlay.tool = currentTool
            holder.overlay.paintColor = currentColor
            holder.overlay.strokes = strokesForPage(position)
            holder.overlay.onStrokeAdded = {
                onOverlayStrokeAdded(position, holder.overlay)
            }
            holder.overlay.onTextNoteRequested = { nx, ny ->
                showTextNoteDialog(nx, ny, holder.overlay)
            }
            overlays[position] = holder.overlay

            // Form overlay (third layer, above annotations). Both overlays
            // overwrite imageView.onMatrixChanged in syncWith, so set one
            // combined listener that invalidates both.
            holder.formOverlay.syncWith(holder.imageView)
            holder.imageView.onMatrixChanged = {
                holder.overlay.invalidate()
                holder.formOverlay.invalidate()
            }
            val pageFields = allFormFields.filter { it.pageIndex == position }
            holder.formOverlay.fields = pageFields
            holder.formOverlay.seedFrom(pageFields)
            // Re-apply user edits (overlay instances are recycled).
            for ((k, v) in masterFieldValues) {
                if (pageFields.any { it.name == k }) holder.formOverlay.fieldValues[k] = v
            }
            holder.formOverlay.invalidate()
            holder.formOverlay.formMode = formModeOn
            holder.formOverlay.onValueChanged = { name, value ->
                masterFieldValues[name] = value
            }
            holder.formOverlay.onTextFieldTapped = { f ->
                showFieldEditDialog(f, holder.formOverlay)
            }
            holder.formOverlay.onChoiceTapped = { f ->
                showFieldChoiceDialog(f, holder.formOverlay)
            }
            formOverlays[position] = holder.formOverlay
        }

        override fun onViewRecycled(holder: PageViewHolder) {
            super.onViewRecycled(holder)
            // Use the stored bound position: bindingAdapterPosition may
            // already be NO_POSITION at recycle time, leaving stale entries.
            if (holder.boundPosition >= 0) {
                overlays.remove(holder.boundPosition)
                formOverlays.remove(holder.boundPosition)
            }
            holder.boundPosition = -1
        }

        fun forEachOverlay(action: (PdfAnnotationView) -> Unit) {
            for (o in overlays.values) action(o)
        }

        fun forEachFormOverlay(action: (PdfFormOverlayView) -> Unit) {
            for (o in formOverlays.values) action(o)
        }

        fun setFormMode(on: Boolean) {
            for (o in formOverlays.values) o.formMode = on
        }
    }
}
