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
import java.io.File
import java.io.FileOutputStream

/**
 * Native PDF viewer: PdfRenderer + pinch-to-zoom pages + annotation tools.
 * No WebView. Annotations persist as JSON in app files.
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

    private var currentTool: PdfAnnotations.Tool = PdfAnnotations.Tool.NONE
    private var currentColor: Int = Color.parseColor("#E8C266")

    // All annotations across pages
    private val allStrokes = mutableListOf<PdfAnnotations.Stroke>()

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

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "PDF"
        docId = intent.getStringExtra(EXTRA_ID) ?: ""
        val uriString = intent.getStringExtra(EXTRA_URI)

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
                runOnUiThread {
                    progress.visibility = View.GONE
                    pageCount = renderer?.pageCount ?: 0
                    viewPager.adapter = PdfPageAdapter(this)
                    pageIndicator.text = if (pageCount > 0) "1 / $pageCount" else "0 / 0"
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

    private fun annotFile(): File {
        val key = if (docId.isNotEmpty()) docId else "local_${System.currentTimeMillis()}"
        return File(filesDir, "pdf_annot_$key.json")
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

    // ---------- Menu ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.pdf_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { onBackPressed(); true }
            R.id.action_highlight -> { selectTool(PdfAnnotations.Tool.HIGHLIGHT); true }
            R.id.action_share -> { sharePdf(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun sharePdf() {
        val file = pdfFile ?: return
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
    }

    inner class PdfPageAdapter(private val ctx: Context) :
        RecyclerView.Adapter<PageViewHolder>() {

        private val overlays = mutableMapOf<Int, PdfAnnotationView>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
            val view = LayoutInflater.from(ctx).inflate(R.layout.item_pdf_page, parent, false)
            return PageViewHolder(view)
        }

        override fun getItemCount(): Int = pageCount

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            holder.imageView.setImageDrawable(null)
            Thread {
                val width = 1080
                val bitmap = renderPage(position, width)
                holder.imageView.post {
                    if (bitmap != null) holder.imageView.setImageBitmap(bitmap)
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
        }

        override fun onViewRecycled(holder: PageViewHolder) {
            super.onViewRecycled(holder)
            overlays.remove(holder.bindingAdapterPosition)
        }

        fun forEachOverlay(action: (PdfAnnotationView) -> Unit) {
            for (o in overlays.values) action(o)
        }
    }
}
