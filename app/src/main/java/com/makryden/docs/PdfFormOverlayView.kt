package com.makryden.docs

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * PROTOTYPE (drop-in ready) - tappable AcroForm field overlay for PDF pages.
 *
 * Follows the same pattern as PdfAnnotationView: transparent View layered over
 * ZoomableImageView, kept in sync via syncWith(), coordinates normalized 0..1
 * (top-left origin) so fields survive zoom/pan.
 *
 * Add as a THIRD layer in item_pdf_page.xml, above PdfAnnotationView:
 *   <com.makryden.docs.PdfFormOverlayView
 *       android:id="@+id/form_overlay"
 *       android:layout_width="match_parent"
 *       android:layout_height="match_parent" />
 *
 * Interaction contract (wired by PdfViewerActivity):
 *   - onTextFieldTapped(field)  -> show an EditText dialog, then call
 *     setFieldValue(field.name, text) and invalidate()
 *   - onCheckboxTapped(field)   -> toggle immediately, call setFieldValue()
 *   - onChoiceTapped(field)     -> show single-choice dialog, call setFieldValue()
 *   - The activity collects overlay.fieldValues and passes them to
 *     PdfFormFields.saveFilled() on Save/Share.
 */
class PdfFormOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** Fields for the page this overlay instance is bound to. */
    var fields: List<PdfFormFields.FormField> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /** Current filled values: field name -> value ("true"/"false" for checkboxes). */
    val fieldValues = mutableMapOf<String, String>()

    /** Show outlines even when no field is focused. Toggle with a "Fill form" button. */
    var formMode: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var onTextFieldTapped: ((PdfFormFields.FormField) -> Unit)? = null
    var onCheckboxTapped: ((PdfFormFields.FormField) -> Unit)? = null
    var onChoiceTapped: ((PdfFormFields.FormField) -> Unit)? = null

    /**
     * Fired whenever a value changes (dialog save or checkbox toggle) so the
     * activity can keep a master map. Overlay instances are recycled by the
     * pager, so values must NOT live only in the overlay.
     */
    var onValueChanged: ((String, String) -> Unit)? = null

    /** Field hit on ACTION_DOWN, awaiting ACTION_UP (tap claim). */
    private var pendingHit: PdfFormFields.FormField? = null

    private var imageView: ZoomableImageView? = null

    private val outlinePaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
        color = Color.parseColor("#E8C266") // gold
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
    }
    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
        color = Color.parseColor("#33E8C266") // translucent gold wash
    }
    private val textPaint = Paint().apply {
        isAntiAlias = true
        color = Color.BLACK
        textSize = 32f
    }
    private val checkPaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
        color = Color.BLACK
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }

    fun syncWith(iv: ZoomableImageView) {
        imageView = iv
        iv.onMatrixChanged = { invalidate() }
    }

    /** Seed the overlay with values already in the PDF (from extraction). */
    fun seedFrom(fields: List<PdfFormFields.FormField>) {
        for (f in fields) {
            when (f.kind) {
                PdfFormFields.Kind.CHECKBOX ->
                    fieldValues[f.name] = f.isChecked.toString()
                PdfFormFields.Kind.TEXT, PdfFormFields.Kind.CHOICE,
                PdfFormFields.Kind.RADIO ->
                    if (f.currentValue.isNotEmpty()) fieldValues[f.name] = f.currentValue
                else -> {}
            }
        }
    }

    fun setFieldValue(name: String, value: String) {
        fieldValues[name] = value
        onValueChanged?.invoke(name, value)
        invalidate()
    }

    fun hasUnsavedChanges(): Boolean = fieldValues.isNotEmpty()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!formMode) return
        val iv = imageView ?: return
        for (f in fields) {
            val (x1, y1) = iv.normalizedToView(f.rect.left, f.rect.top)
            val (x2, y2) = iv.normalizedToView(f.rect.right, f.rect.bottom)
            val left = min(x1, x2); val right = max(x1, x2)
            val top = min(y1, y2); val bottom = max(y1, y2)
            canvas.drawRect(left, top, right, bottom, fillPaint)
            canvas.drawRect(left, top, right, bottom, outlinePaint)
            // Render the current value inside the field
            when (f.kind) {
                PdfFormFields.Kind.TEXT, PdfFormFields.Kind.CHOICE,
                PdfFormFields.Kind.RADIO -> {
                    val v = fieldValues[f.name] ?: f.currentValue
                    if (v.isNotEmpty()) {
                        val h = bottom - top
                        textPaint.textSize = (h * 0.55f).coerceIn(16f, 64f)
                        // Clip text to field width
                        val maxChars = ((right - left) / (textPaint.textSize * 0.55f)).toInt().coerceAtLeast(1)
                        val shown = if (v.length > maxChars) v.take(maxChars - 1) + "..." else v
                        canvas.drawText(shown, left + 6f, bottom - h * 0.28f, textPaint)
                    }
                }
                PdfFormFields.Kind.CHECKBOX -> {
                    val checked = fieldValues[f.name]?.toBoolean() ?: f.isChecked
                    if (checked) {
                        canvas.drawLine(left + 4f, (top + bottom) / 2f, (left + right) / 2f, bottom - 4f, checkPaint)
                        canvas.drawLine((left + right) / 2f, bottom - 4f, right - 4f, top + 4f, checkPaint)
                    }
                }
                else -> {}
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!formMode) return false
        val iv = imageView ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val (nx, ny) = iv.viewToNormalized(event.x, event.y)
                val hit = fields.lastOrNull { f ->
                    nx >= f.rect.left && nx <= f.rect.right &&
                    ny >= f.rect.top && ny <= f.rect.bottom
                }
                pendingHit = hit
                if (hit != null) {
                    // Claim the gesture so the pager does not swipe mid-tap.
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                // No field hit: let the annotation overlay / image handle it.
                return false
            }
            MotionEvent.ACTION_UP -> {
                val hit = pendingHit
                pendingHit = null
                if (hit == null) return false
                when (hit.kind) {
                    PdfFormFields.Kind.TEXT -> onTextFieldTapped?.invoke(hit)
                    PdfFormFields.Kind.CHECKBOX -> {
                        val cur = fieldValues[hit.name]?.toBoolean() ?: hit.isChecked
                        val next = (!cur).toString()
                        fieldValues[hit.name] = next
                        onValueChanged?.invoke(hit.name, next)
                        invalidate()
                        onCheckboxTapped?.invoke(hit)
                    }
                    PdfFormFields.Kind.CHOICE, PdfFormFields.Kind.RADIO ->
                        onChoiceTapped?.invoke(hit)
                    else -> {}
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pendingHit = null
                return true
            }
            else -> return pendingHit != null
        }
    }
}

