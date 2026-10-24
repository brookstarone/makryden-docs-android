package com.makryden.docs

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Exports an annotated PDF: renders every page of the source PDF to a
 * bitmap, draws the user's annotation strokes onto the bitmap, and writes
 * the result into a new PDF file via android.graphics.pdf.PdfDocument.
 *
 * The output is a real PDF any viewer can open, with highlights,
 * underlines, rectangles, freehand strokes, signatures, and text notes
 * burned into the page image. No new dependencies required.
 *
 * Coordinates in PdfAnnotations.Stroke are normalized 0..1 relative to
 * the page, so they map directly onto the rendered bitmap pixels.
 */
object PdfExporter {

    /** Called after each page is written. Runs on the caller's thread. */
    fun interface ProgressListener {
        fun onPageDone(pageIndex: Int, pageCount: Int)
    }

    /**
     * Export [source] plus [strokes] to [output].
     * @throws Exception if the source cannot be opened or the write fails.
     */
    fun export(
        source: File,
        strokes: List<PdfAnnotations.Stroke>,
        output: File,
        listener: ProgressListener? = null
    ) {
        val pfd = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val doc = PdfDocument()
        try {
            val pageCount = renderer.pageCount
            if (pageCount <= 0) throw Exception("Source PDF has no pages")
            val byPage = strokes.groupBy { it.pageIndex }

            for (i in 0 until pageCount) {
                val page = renderer.openPage(i)
                try {
                    val bitmap = renderPageBitmap(renderer, page)
                    try {
                        drawStrokes(bitmap, byPage[i] ?: emptyList())
                        writeBitmapPage(doc, bitmap, i)
                    } finally {
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                } finally {
                    page.close()
                }
                listener?.onPageDone(i, pageCount)
            }

            FileOutputStream(output).use { out ->
                doc.writeTo(out)
            }
        } finally {
            try { doc.close() } catch (e: Exception) { }
            try { renderer.close() } catch (e: Exception) { }
            try { pfd.close() } catch (e: Exception) { }
        }
    }

    // ---------- Rendering ----------

    private fun renderPageBitmap(renderer: PdfRenderer, page: PdfRenderer.Page): Bitmap {
        // Target ~1700px on the long side: crisp on phones/tablets, sane memory.
        val targetLong = 1700
        val longSide = max(page.width, page.height)
        val scale = if (longSide > 0) targetLong.toFloat() / longSide else 2f
        val w = (page.width * scale).toInt().coerceAtLeast(1)
        val h = (page.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    private fun writeBitmapPage(doc: PdfDocument, bitmap: Bitmap, index: Int) {
        // Page size in points (1/72 inch). Fixed 612pt width, height by aspect.
        val pageW = 612
        val pageH = (612f * bitmap.height / bitmap.width).toInt().coerceAtLeast(1)
        val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, index).create()
        val page = doc.startPage(pageInfo)
        try {
            page.canvas.drawBitmap(bitmap, null, Rect(0, 0, pageW, pageH), null)
        } finally {
            doc.finishPage(page)
        }
    }

    // ---------- Annotation drawing (mirrors PdfAnnotationView) ----------

    private fun drawStrokes(bitmap: Bitmap, strokes: List<PdfAnnotations.Stroke>) {
        if (strokes.isEmpty()) return
        val canvas = Canvas(bitmap)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val fillPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
        val strokePaint = Paint().apply {
            style = Paint.Style.STROKE
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (s in strokes) {
            try {
                drawStroke(canvas, w, h, s, fillPaint, strokePaint)
            } catch (e: Exception) {
                // One bad stroke must not kill the whole export.
            }
        }
    }

    private fun drawStroke(
        canvas: Canvas,
        bw: Float,
        bh: Float,
        s: PdfAnnotations.Stroke,
        fillPaint: Paint,
        strokePaint: Paint
    ) {
        val left = min(s.rect.left, s.rect.right) * bw
        val right = max(s.rect.left, s.rect.right) * bw
        val top = min(s.rect.top, s.rect.bottom) * bh
        val bottom = max(s.rect.top, s.rect.bottom) * bh
        // Stroke width: the stored value is dp-ish (default 3). Scale to bitmap.
        val wPx = max(3f, s.strokeWidth * bw / 300f)

        when (s.tool) {
            PdfAnnotations.Tool.HIGHLIGHT -> {
                fillPaint.color = withAlpha(s.color, 0.35f)
                canvas.drawRect(left, top, right, bottom, fillPaint)
            }
            PdfAnnotations.Tool.UNDERLINE -> {
                fillPaint.color = s.color
                val uy = bottom - wPx
                canvas.drawRect(left, uy, right, bottom, fillPaint)
            }
            PdfAnnotations.Tool.RECTANGLE -> {
                strokePaint.color = s.color
                strokePaint.strokeWidth = wPx
                canvas.drawRect(left, top, right, bottom, strokePaint)
            }
            PdfAnnotations.Tool.FREEHAND, PdfAnnotations.Tool.SIGNATURE -> {
                strokePaint.color = s.color
                strokePaint.strokeWidth =
                    if (s.tool == PdfAnnotations.Tool.SIGNATURE) wPx * 1.5f else wPx
                val pts = s.points
                if (pts.size >= 2) {
                    for (i in 0 until pts.size - 1) {
                        val ax = pts[i].first * bw
                        val ay = pts[i].second * bh
                        val bx = pts[i + 1].first * bw
                        val by = pts[i + 1].second * bh
                        canvas.drawLine(ax, ay, bx, by, strokePaint)
                    }
                } else if (pts.size == 1) {
                    canvas.drawCircle(pts[0].first * bw, pts[0].second * bh, wPx / 2f, strokePaint)
                }
            }
            PdfAnnotations.Tool.TEXT_NOTE -> {
                drawTextNote(canvas, bw, bh, s, left, top, right, bottom, wPx)
            }
            else -> {}
        }
    }

    /**
     * Draws a text note as a visible marker pin plus the note text in a
     * box, so the note content itself survives in the exported PDF
     * (a bare pin would be useless in another viewer).
     */
    private fun drawTextNote(
        canvas: Canvas,
        bw: Float,
        bh: Float,
        s: PdfAnnotations.Stroke,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        wPx: Float
    ) {
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val pinPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
        pinPaint.color = s.color
        val r = max(12f, wPx * 4f)
        canvas.drawCircle(cx, cy, r, pinPaint)
        pinPaint.color = Color.WHITE
        canvas.drawCircle(cx, cy, r * 0.35f, pinPaint)

        val text = s.text.trim()
        if (text.isEmpty()) return

        // Text box to the right of the pin (or left if near the right edge).
        val textSize = max(22f, bw / 45f)
        val textPaint = Paint().apply {
            color = Color.WHITE
            this.textSize = textSize
            isAntiAlias = true
        }
        val pad = textSize * 0.6f
        val maxLineWidth = bw * 0.45f
        val lines = wrapText(text, textPaint, maxLineWidth).take(6)
        if (lines.isEmpty()) return

        var widest = 0f
        for (line in lines) {
            widest = max(widest, textPaint.measureText(line))
        }
        val boxW = widest + pad * 2
        val boxH = lines.size * textSize * 1.35f + pad * 2

        var boxLeft = cx + r + pad
        if (boxLeft + boxW > bw - pad) {
            boxLeft = cx - r - pad - boxW
            if (boxLeft < pad) boxLeft = pad
        }
        var boxTop = cy - boxH / 2f
        if (boxTop < pad) boxTop = pad
        if (boxTop + boxH > bh - pad) boxTop = bh - pad - boxH

        val boxPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
        boxPaint.color = Color.argb(230, 22, 15, 43) // dark, matches brand
        val boxRect = RectF(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH)
        canvas.drawRoundRect(boxRect, pad / 2, pad / 2, boxPaint)
        var y = boxTop + pad + textSize
        for (line in lines) {
            canvas.drawText(line, boxLeft + pad, y, textPaint)
            y += textSize * 1.35f
        }
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        val paragraphs = text.split("\n")
        for (para in paragraphs) {
            val words = para.split(" ").filter { it.isNotEmpty() }
            if (words.isEmpty()) {
                lines.add("")
                continue
            }
            var current = StringBuilder()
            for (word in words) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (paint.measureText(candidate) <= maxWidth || current.isEmpty()) {
                    current = StringBuilder(candidate)
                } else {
                    lines.add(current.toString())
                    current = StringBuilder(word)
                }
            }
            if (current.isNotEmpty()) lines.add(current.toString())
        }
        return lines
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (Color.alpha(color) * alpha).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }
}
