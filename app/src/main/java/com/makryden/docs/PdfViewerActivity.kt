package com.makryden.docs

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
 * Native PDF viewer using Android's PdfRenderer. No WebView.
 * Features: page swiping, highlight annotations, share.
 */
class PdfViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "id"           // pdf_documents row id (for saved PDFs)
        const val EXTRA_TITLE = "title"     // filename
        const val EXTRA_URI = "uri"         // content URI (for shared PDFs)
    }

    private lateinit var viewPager: ViewPager2
    private lateinit var pageIndicator: TextView
    private lateinit var progress: ProgressBar
    private lateinit var toolbar: Toolbar

    private var renderer: PdfRenderer? = null
    private var parcelFd: ParcelFileDescriptor? = null
    private var pageCount = 0
    private var pdfFile: File? = null
    private var highlightMode = false

    // Annotations: page index -> list of highlight rects (normalized 0..1)
    private val highlights = mutableMapOf<Int, MutableList<RectF>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_viewer)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "PDF"
        val docId = intent.getStringExtra(EXTRA_ID) ?: ""
        val uriString = intent.getStringExtra(EXTRA_URI)

        toolbar = findViewById(R.id.toolbar)
        toolbar.title = title
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        viewPager = findViewById(R.id.view_pager)
        pageIndicator = findViewById(R.id.page_indicator)
        progress = findViewById(R.id.progress)

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                pageIndicator.text = "${position + 1} / $pageCount"
            }
        })

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
                    pageIndicator.text = "1 / $pageCount"
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

    private fun copyUriToTemp(uri: Uri): File {
        val outFile = File(cacheDir, "shared_${System.currentTimeMillis()}.pdf")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
        return outFile
    }

    private fun downloadPdf(docId: String): File {
        val session = SessionStore(this)
        val token = session.getAccessToken() ?: throw Exception("Not signed in")
        // Get the storage path
        val url = java.net.URL("https://bamrgsjgvakyucyqtwlg.supabase.co/rest/v1/pdf_documents?select=storage_path,filename&id=eq.$docId&limit=1")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.setRequestProperty("apikey", getAnonKey())
        conn.setRequestProperty("Authorization", "Bearer $token")
        val reader = java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream))
        val json = reader.readText()
        reader.close()
        val arr = org.json.JSONArray(json)
        if (arr.length() == 0) throw Exception("PDF not found")
        val storagePath = arr.getJSONObject(0).getString("storage_path")
        val filename = arr.getJSONObject(0).optString("filename", "document.pdf")

        // Download from storage
        val dlUrl = java.net.URL("https://bamrgsjgvakyucyqtwlg.supabase.co/storage/v1/object/user-pdfs/$storagePath")
        val dlConn = dlUrl.openConnection() as java.net.HttpURLConnection
        dlConn.setRequestProperty("apikey", getAnonKey())
        dlConn.setRequestProperty("Authorization", "Bearer $token")
        val outFile = File(cacheDir, "pdf_${docId}.pdf")
        dlConn.inputStream.use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
        return outFile
    }

    private fun getAnonKey(): String {
        // Same public anon key as SupabaseClient
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
                val height = (page.height * scale).toInt()
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            } finally {
                page.close()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.pdf_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_highlight)?.isChecked = highlightMode
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                true
            }
            R.id.action_highlight -> {
                highlightMode = !highlightMode
                item.isChecked = highlightMode
                Toast.makeText(
                    this,
                    if (highlightMode) "Highlight mode: drag on the page" else "Highlight off",
                    Toast.LENGTH_SHORT
                ).show()
                // Refresh current page to update overlay
                viewPager.adapter?.notifyDataSetChanged()
                true
            }
            R.id.action_share -> {
                sharePdf()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun sharePdf() {
        val file = pdfFile ?: return
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "application/pdf"
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "Share PDF via"))
        } catch (e: Exception) {
            Toast.makeText(this, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            renderer?.close()
            parcelFd?.close()
        } catch (e: Exception) { }
    }

    /**
     * ViewHolder for a single PDF page with annotation overlay.
     */
    inner class PageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.page_image)
        val overlay: AnnotationOverlay = view.findViewById(R.id.annotation_overlay)
    }

    inner class PdfPageAdapter(private val context: Context) :
        RecyclerView.Adapter<PageViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
            val view = LayoutInflater.from(context)
                .inflate(R.layout.item_pdf_page, parent, false)
            return PageViewHolder(view)
        }

        override fun getItemCount(): Int = pageCount

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            // Render on background thread
            holder.imageView.setImageBitmap(null)
            Thread {
                val width = holder.imageView.width.takeIf { it > 0 } ?: 1080
                val bitmap = renderPage(position, width)
                holder.imageView.post {
                    if (bitmap != null) {
                        holder.imageView.setImageBitmap(bitmap)
                    }
                }
            }.start()

            // Setup annotation overlay
            holder.overlay.pageIndex = position
            holder.overlay.highlights = highlights.getOrPut(position) { mutableListOf() }
            holder.overlay.highlightMode = highlightMode
            holder.overlay.onHighlightAdded = {
                // Could persist to Supabase here
            }
        }
    }

    /**
     * Transparent overlay for drawing highlight annotations.
     */
    class AnnotationOverlay(context: Context, attrs: android.util.AttributeSet?) :
        View(context, attrs) {

        var pageIndex: Int = 0
        var highlights: MutableList<RectF> = mutableListOf()
        var highlightMode: Boolean = false
        var onHighlightAdded: (() -> Unit)? = null

        private val paint = Paint().apply {
            color = Color.parseColor("#FFE8C266") // translucent gold
            style = Paint.Style.FILL
        }
        private var startX = 0f
        private var startY = 0f
        private var currentRect: RectF? = null

        init {
            setBackgroundColor(Color.TRANSPARENT)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for (rect in highlights) {
                // Convert normalized to pixels
                canvas.drawRect(
                    rect.left * width,
                    rect.top * height,
                    rect.right * width,
                    rect.bottom * height,
                    paint
                )
            }
            currentRect?.let {
                canvas.drawRect(
                    it.left * width,
                    it.top * height,
                    it.right * width,
                    it.bottom * height,
                    paint
                )
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!highlightMode) return false
            // Disable ViewPager2 swipe while highlighting
            parent.requestDisallowInterceptTouchEvent(true)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x / width
                    startY = event.y / height
                    currentRect = RectF(startX, startY, startX, startY)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    currentRect?.let {
                        it.right = event.x / width
                        it.bottom = event.y / height
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    currentRect?.let {
                        // Normalize
                        val left = minOf(it.left, it.right)
                        val right = maxOf(it.left, it.right)
                        val top = minOf(it.top, it.bottom)
                        val bottom = maxOf(it.top, it.bottom)
                        // Only save if meaningful size
                        if ((right - left) > 0.02 && (bottom - top) > 0.005) {
                            highlights.add(RectF(left, top, right, bottom))
                            onHighlightAdded?.invoke()
                        }
                    }
                    currentRect = null
                    invalidate()
                    parent.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
