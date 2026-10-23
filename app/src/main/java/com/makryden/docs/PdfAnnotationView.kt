package com.makryden.docs

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Transparent overlay for PDF annotations. Supports:
 * HIGHLIGHT, UNDERLINE, TEXT_NOTE, RECTANGLE, FREEHAND, SIGNATURE.
 *
 * Coordinates are normalized 0..1 relative to the page image.
 * The overlay must be kept in sync with the ZoomableImageView's matrix
 * via syncWith(imageView).
 */
class PdfAnnotationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var tool: PdfAnnotations.Tool = PdfAnnotations.Tool.NONE
    var strokes: MutableList<PdfAnnotations.Stroke> = mutableListOf()
    var paintColor: Int = Color.parseColor("#E8C266")
    var strokeWidthDp: Float = 3f
    var onStrokeAdded: (() -> Unit)? = null
    var onTextNoteRequested: ((x: Float, y: Float) -> Unit)? = null

    private var imageView: ZoomableImageView? = null

    // In-progress drawing state (view pixels)
    private var startVX = 0f
    private var startVY = 0f
    private var currentRect: RectF? = null
    private var currentPoints: MutableList<Pair<Float, Float>>? = null // view px

    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val strokePaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val underlinePaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
    }

    fun syncWith(iv: ZoomableImageView) {
        imageView = iv
        iv.onMatrixChanged = { invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val iv = imageView ?: return
        for (s in strokes) {
            drawStroke(canvas, iv, s)
        }
        // In-progress
        currentRect?.let { r ->
            drawStrokePreview(canvas, iv, tool, r, currentPoints)
        }
    }

    private fun drawStroke(
        canvas: Canvas,
        iv: ZoomableImageView,
        s: PdfAnnotations.Stroke
    ) {
        val (x1, y1) = iv.normalizedToView(s.rect.left, s.rect.top)
        val (x2, y2) = iv.normalizedToView(s.rect.right, s.rect.bottom)
        val left = min(x1, x2); val right = max(x1, x2)
        val top = min(y1, y2); val bottom = max(y1, y2)
        val scale = iv.normalizedToViewScale()
        val wPx = max(2f, s.strokeWidth / 100f * scale * 0.15f + 2f)

        when (s.tool) {
            PdfAnnotations.Tool.HIGHLIGHT -> {
                fillPaint.color = withAlpha(s.color, 0.35f)
                // Highlight hugs text height: use rect as-is
                canvas.drawRect(left, top, right, bottom, fillPaint)
            }
            PdfAnnotations.Tool.UNDERLINE -> {
                underlinePaint.color = s.color
                val uy = bottom - wPx
                canvas.drawRect(left, uy, right, bottom, underlinePaint)
            }
            PdfAnnotations.Tool.RECTANGLE -> {
                strokePaint.color = s.color
                strokePaint.strokeWidth = wPx
                canvas.drawRect(left, top, right, bottom, strokePaint)
            }
            PdfAnnotations.Tool.FREEHAND, PdfAnnotations.Tool.SIGNATURE -> {
                strokePaint.color = s.color
                strokePaint.strokeWidth = if (s.tool == PdfAnnotations.Tool.SIGNATURE) wPx * 1.5f else wPx
                val pts = s.points
                if (pts.size >= 2) {
                    for (i in 0 until pts.size - 1) {
                        val (ax, ay) = iv.normalizedToView(pts[i].first, pts[i].second)
                        val (bx, by) = iv.normalizedToView(pts[i + 1].first, pts[i + 1].second)
                        canvas.drawLine(ax, ay, bx, by, strokePaint)
                    }
                } else if (pts.size == 1) {
                    val (ax, ay) = iv.normalizedToView(pts[0].first, pts[0].second)
                    canvas.drawCircle(ax, ay, wPx / 2f, strokePaint)
                }
            }
            PdfAnnotations.Tool.TEXT_NOTE -> {
                // Draw a small marker pin
                fillPaint.color = s.color
                val cx = (left + right) / 2f
                val cy = (top + bottom) / 2f
                val r = max(10f, wPx * 4f)
                canvas.drawCircle(cx, cy, r, fillPaint)
                // Inner dot
                fillPaint.color = Color.WHITE
                canvas.drawCircle(cx, cy, r * 0.35f, fillPaint)
            }
            else -> {}
        }
    }

    private fun drawStrokePreview(
        canvas: Canvas,
        iv: ZoomableImageView,
        t: PdfAnnotations.Tool,
        rect: RectF,
        points: List<Pair<Float, Float>>?
    ) {
        // Convert view-px rect to a temp stroke for preview rendering
        val iv2 = imageView ?: return
        val (nl, nt) = iv2.viewToNormalized(rect.left, rect.top)
        val (nr, nb) = iv2.viewToNormalized(rect.right, rect.bottom)
        val preview = PdfAnnotations.Stroke(
            tool = t,
            pageIndex = 0,
            rect = RectF(nl, nt, nr, nb),
            points = points?.map { iv2.viewToNormalized(it.first, it.second) } ?: emptyList(),
            color = paintColor,
            strokeWidth = strokeWidthDp
        )
        drawStroke(canvas, iv, preview)
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (Color.alpha(color) * alpha).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (tool == PdfAnnotations.Tool.NONE) return false
        val iv = imageView ?: return false
        // Take over touch: disable pager swipe and image pan
        parent.requestDisallowInterceptTouchEvent(true)
        iv.touchPassthrough = true

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startVX = event.x; startVY = event.y
                if (tool == PdfAnnotations.Tool.TEXT_NOTE) {
                    val (nx, ny) = iv.viewToNormalized(event.x, event.y)
                    onTextNoteRequested?.invoke(nx, ny)
                    endGesture()
                    return true
                }
                if (tool == PdfAnnotations.Tool.FREEHAND || tool == PdfAnnotations.Tool.SIGNATURE) {
                    currentPoints = mutableListOf(Pair(event.x, event.y))
                } else {
                    currentRect = RectF(event.x, event.y, event.x, event.y)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (tool == PdfAnnotations.Tool.FREEHAND || tool == PdfAnnotations.Tool.SIGNATURE) {
                    currentPoints?.add(Pair(event.x, event.y))
                    // Update bounding rect for preview
                    currentRect = boundingRect(currentPoints!!)
                } else {
                    currentRect?.let {
                        it.right = event.x
                        it.bottom = event.y
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.action == MotionEvent.ACTION_UP) {
                    commitStroke(iv)
                }
                currentRect = null
                currentPoints = null
                invalidate()
                endGesture()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun boundingRect(pts: List<Pair<Float, Float>>): RectF {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
        var r = Float.MIN_VALUE; var b = Float.MIN_VALUE
        for ((x, y) in pts) {
            l = min(l, x); t = min(t, y); r = max(r, x); b = max(b, y)
        }
        // Minimum size so taps register
        if (r - l < 8f) r = l + 8f
        if (b - t < 8f) b = t + 8f
        return RectF(l, t, r, b)
    }

    private fun commitStroke(iv: ZoomableImageView) {
        val rect = currentRect ?: return
        // Ignore accidental taps for rect tools (but allow for freehand dots)
        val isFreehand = tool == PdfAnnotations.Tool.FREEHAND || tool == PdfAnnotations.Tool.SIGNATURE
        if (!isFreehand) {
            if (abs(rect.right - rect.left) < 12f || abs(rect.bottom - rect.top) < 8f) return
        }
        val (nl, nt) = iv.viewToNormalized(rect.left, rect.top)
        val (nr, nb) = iv.viewToNormalized(rect.right, rect.bottom)
        val normRect = RectF(min(nl, nr), min(nt, nb), max(nl, nr), max(nt, nb))
        val normPoints = currentPoints?.map { iv.viewToNormalized(it.first, it.second) } ?: emptyList()
        strokes.add(PdfAnnotations.Stroke(
            tool = tool,
            pageIndex = 0, // set by adapter
            rect = normRect,
            points = normPoints,
            color = paintColor,
            strokeWidth = strokeWidthDp
        ))
        onStrokeAdded?.invoke()
    }

    private fun endGesture() {
        parent.requestDisallowInterceptTouchEvent(false)
        imageView?.touchPassthrough = false
    }

    /** Add a text note at normalized coords (called after dialog input). */
    fun addTextNote(nx: Float, ny: Float, text: String) {
        val size = 0.03f
        strokes.add(PdfAnnotations.Stroke(
            tool = PdfAnnotations.Tool.TEXT_NOTE,
            pageIndex = 0,
            rect = RectF(nx - size / 2, ny - size / 2, nx + size / 2, ny + size / 2),
            text = text,
            color = paintColor,
            strokeWidth = strokeWidthDp
        ))
        invalidate()
        onStrokeAdded?.invoke()
    }
}
