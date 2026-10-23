package com.makryden.docs

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min

/**
 * ImageView with pinch-to-zoom and pan. Double-tap to toggle zoom.
 * Used for PDF page rendering.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : AppCompatImageView(context, attrs, defStyle) {

    private val matrix = Matrix()
    private val matrixValues = FloatArray(9)
    private var mode = MODE_NONE

    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    private var minScale = 1f
    private var maxScale = 4f
    private var saveScale = 1f

    // For annotation coordinate mapping: the view reports its current
    // image matrix so overlays can align.
    var onMatrixChanged: (() -> Unit)? = null

    // When true, touch events pass through to parent (for annotation mode)
    var touchPassthrough = false

    companion object {
        private const val MODE_NONE = 0
        private const val MODE_DRAG = 1
        private const val MODE_ZOOM = 2
    }

    init {
        scaleType = ScaleType.MATRIX
        imageMatrix = matrix

        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                mode = MODE_ZOOM
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                var scaleFactor = detector.scaleFactor
                val origScale = saveScale
                saveScale *= scaleFactor
                if (saveScale > maxScale) {
                    saveScale = maxScale
                    scaleFactor = maxScale / origScale
                } else if (saveScale < minScale) {
                    saveScale = minScale
                    scaleFactor = minScale / origScale
                }
                matrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
                fixTranslation()
                imageMatrix = matrix
                onMatrixChanged?.invoke()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                mode = MODE_NONE
            }
        })

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                // Toggle between 1x and 2.5x
                val target = if (saveScale < 1.5f) 2.5f else 1f
                val factor = target / saveScale
                saveScale = target
                matrix.postScale(factor, factor, e.x, e.y)
                if (target == 1f) {
                    resetZoom()
                } else {
                    fixTranslation()
                }
                imageMatrix = matrix
                onMatrixChanged?.invoke()
                return true
            }
        })

        // Drag handling
        setOnTouchListener { _, event ->
            if (touchPassthrough) return@setOnTouchListener false
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)

            val curr = PointF(event.x, event.y)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    last.set(curr)
                    mode = MODE_DRAG
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == MODE_DRAG && saveScale > minScale) {
                        val dx = curr.x - last.x
                        val dy = curr.y - last.y
                        matrix.postTranslate(dx, dy)
                        fixTranslation()
                        imageMatrix = matrix
                        onMatrixChanged?.invoke()
                    }
                    last.set(curr)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mode = MODE_NONE
                }
            }
            // Always consume so ViewPager2 doesn't steal during zoom/pan
            true
        }
    }

    private val last = PointF()

    private fun resetZoom() {
        matrix.reset()
        fitImageToView()
        saveScale = 1f
    }

    private fun fitImageToView() {
        val d = drawable ?: return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW == 0f || viewH == 0f) return
        val drawableW = d.intrinsicWidth.toFloat()
        val drawableH = d.intrinsicHeight.toFloat()
        if (drawableW == 0f || drawableH == 0f) return

        matrix.reset()
        val scale = min(viewW / drawableW, viewH / drawableH)
        minScale = scale
        // Center
        val dx = (viewW - drawableW * scale) / 2f
        val dy = (viewH - drawableH * scale) / 2f
        matrix.postScale(scale, scale)
        matrix.postTranslate(dx, dy)
        saveScale = 1f
        imageMatrix = matrix
    }

    private fun fixTranslation() {
        matrix.getValues(matrixValues)
        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]
        val scaleX = matrixValues[Matrix.MSCALE_X]

        val d = drawable ?: return
        val drawableW = d.intrinsicWidth * scaleX
        val drawableH = d.intrinsicHeight * scaleX
        val viewW = width.toFloat()
        val viewH = height.toFloat()

        var fixX = 0f
        var fixY = 0f

        // If image is smaller than view, center it. Otherwise clamp.
        if (drawableW < viewW) {
            fixX = (viewW - drawableW) / 2f - transX
        } else {
            if (transX > 0) fixX = -transX
            else if (transX + drawableW < viewW) fixX = viewW - (transX + drawableW)
        }
        if (drawableH < viewH) {
            fixY = (viewH - drawableH) / 2f - transY
        } else {
            if (transY > 0) fixY = -transY
            else if (transY + drawableH < viewH) fixY = viewH - (transY + drawableH)
        }
        if (fixX != 0f || fixY != 0f) {
            matrix.postTranslate(fixX, fixY)
        }
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        // Fit when a new page bitmap is set
        post { fitImageToView(); onMatrixChanged?.invoke() }
    }

    override fun setFrame(l: Int, t: Int, r: Int, b: Int): Boolean {
        val changed = super.setFrame(l, t, r, b)
        if (changed) fitImageToView()
        return changed
    }

    /** Current image matrix for overlay alignment. */
    fun currentMatrix(): Matrix = Matrix(matrix)

    /** Map a point in view coordinates to normalized image coordinates (0..1). */
    fun viewToNormalized(x: Float, y: Float): Pair<Float, Float> {
        val d = drawable ?: return Pair(0f, 0f)
        val inverse = Matrix()
        matrix.invert(inverse)
        val pts = floatArrayOf(x, y)
        inverse.mapPoints(pts)
        val nx = (pts[0] / d.intrinsicWidth).coerceIn(0f, 1f)
        val ny = (pts[1] / d.intrinsicHeight).coerceIn(0f, 1f)
        return Pair(nx, ny)
    }

    /** Map normalized image coordinates (0..1) to view coordinates. */
    fun normalizedToView(nx: Float, ny: Float): Pair<Float, Float> {
        val d = drawable ?: return Pair(0f, 0f)
        val pts = floatArrayOf(nx * d.intrinsicWidth, ny * d.intrinsicHeight)
        matrix.mapPoints(pts)
        return Pair(pts[0], pts[1])
    }

    /** Scale factor from normalized units to view pixels (for stroke widths). */
    fun normalizedToViewScale(): Float {
        matrix.getValues(matrixValues)
        val d = drawable ?: return 1f
        return matrixValues[Matrix.MSCALE_X] * d.intrinsicWidth
    }

    fun getZoom(): Float = saveScale / max(minScale, 0.01f)
}
