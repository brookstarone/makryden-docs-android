package com.makryden.docs

import android.graphics.RectF
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDNonTerminalField
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import java.io.File

/**
 * PROTOTYPE (drop-in ready) - AcroForm field extraction + save-back via PDFBox-Android.
 *
 * Architecture decision: keep android.graphics.pdf.PdfRenderer for DISPLAY (fast,
 * native) and use PDFBox ONLY for form structure + saving. This keeps the change
 * surgical: no renderer swap, no new viewer.
 *
 * Requires in build.gradle.kts:
 *     implementation("com.tom-roush:pdfbox-android:2.0.27.0")
 * And once, before any PDFBox call (e.g. in Application.onCreate or
 * PdfViewerActivity.onCreate):
 *     PDFBoxResourceLoader.init(applicationContext)
 *
 * Coordinate system: PDF points are bottom-left origin. The app's annotation
 * system uses normalized 0..1 coords with TOP-left origin. Conversion flips Y.
 */
object PdfFormFields {

    private const val TAG = "PdfFormFields"

    enum class Kind { TEXT, CHECKBOX, RADIO, CHOICE, BUTTON, SIGNATURE, UNKNOWN }

    /**
     * One fillable widget on one page. A single logical field may appear as
     * multiple entries if it has widgets on several pages.
     */
    data class FormField(
        val name: String,          // fully qualified field name, e.g. "topmostSubform[0].FirstName"
        val kind: Kind,
        val pageIndex: Int,
        val rect: RectF,           // normalized 0..1, top-left origin (matches annotation system)
        val options: List<String> = emptyList(), // for CHOICE / RADIO
        val currentValue: String = "",
        val isChecked: Boolean = false
    )

    /** True if the document has an AcroForm with at least one terminal field. */
    fun hasForm(file: File): Boolean {
        return try {
            PDDocument.load(file).use { doc ->
                val form = doc.documentCatalog.acroForm ?: return false
                form.fields.isNotEmpty()
            }
        } catch (e: Exception) {
            Log.w(TAG, "hasForm failed: ${e.message}")
            false
        }
    }

    /** True if the document has a dynamic XFA form (not fillable via PDFBox). */
    fun hasXfa(file: File): Boolean {
        return try {
            PDDocument.load(file).use { doc ->
                doc.documentCatalog.acroForm?.xfa != null
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Extract every fillable widget, grouped by page.
     * Walks page annotations (most reliable page mapping) rather than the
     * field tree, because widget.page is not always populated.
     * Must be called on a background thread.
     */
    fun extract(file: File): List<FormField> {
        val out = mutableListOf<FormField>()
        try {
            PDDocument.load(file).use { doc ->
                val form = doc.documentCatalog.acroForm ?: return out
                // Detect XFA: PDFBox cannot fill dynamic XFA forms.
                if (form.xfa != null) {
                    Log.w(TAG, "Document contains XFA - dynamic XFA is not fillable via PDFBox")
                    // Still return AcroForm fields; XFA-only fields will be missing.
                }
                // Widget -> field map: PDAnnotationWidget.getParent() is commented
                // out in this PDFBox-Android port, so resolve each widget's field
                // via its shared COSDictionary (same parsed instance on both sides).
                val widgetToField = mutableMapOf<COSDictionary, PDField>()
                fun collectFields(field: PDField) {
                    for (w in field.widgets) {
                        widgetToField[w.cosObject] = field
                    }
                    if (field is PDNonTerminalField) {
                        for (child in field.children) collectFields(child)
                    }
                }
                for (f in form.fields) collectFields(f)
                val pages = doc.pages
                for (pageIndex in 0 until doc.numberOfPages) {
                    val page = pages.get(pageIndex)
                    val mediaBox = page.mediaBox
                    val pageW = mediaBox.width
                    val pageH = mediaBox.height
                    if (pageW <= 0f || pageH <= 0f) continue
                    for (annot in page.annotations) {
                        if (annot !is PDAnnotationWidget) continue
                        val field: PDField = widgetToField[annot.cosObject] ?: continue
                        val rect = annot.rectangle ?: continue
                        // PDF (bottom-left origin) -> normalized (top-left origin)
                        val left = (rect.lowerLeftX / pageW).coerceIn(0f, 1f)
                        val right = (rect.upperRightX / pageW).coerceIn(0f, 1f)
                        val top = (1f - rect.upperRightY / pageH).coerceIn(0f, 1f)
                        val bottom = (1f - rect.lowerLeftY / pageH).coerceIn(0f, 1f)
                        out.add(
                            FormField(
                                name = field.fullyQualifiedName ?: field.partialName ?: "field",
                                kind = kindOf(field),
                                pageIndex = pageIndex,
                                rect = RectF(left, top, right, bottom),
                                options = optionsOf(field),
                                currentValue = valueOf(field),
                                isChecked = checkedOf(field)
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "extract failed", e)
        }
        return out
    }

    /**
     * Write filled values back into a copy of the PDF.
     * @param values map of fully-qualified field name -> value. For checkboxes
     *   use "true"/"false". For radio/choice use the option string.
     * @return the output file, or null on failure.
     * Must be called on a background thread.
     */
    fun saveFilled(input: File, values: Map<String, String>, output: File): File? {
        return try {
            PDDocument.load(input).use { doc ->
                val form = doc.documentCatalog.acroForm
                    ?: throw IllegalStateException("No AcroForm in document")
                // Ask viewers to regenerate appearances if a field lacks one.
                // PDFBox also builds appearance streams in setValue() for
                // text fields, so values render in other readers too.
                try { form.needAppearances = true } catch (e: Exception) { }
                var applied = 0
                for (field in form.fieldTree) {
                    val v = values[field.fullyQualifiedName] ?: continue
                    try {
                        when (field) {
                            is PDTextField -> field.value = v
                            is PDCheckBox -> if (v == "true") field.check() else field.unCheck()
                            is PDRadioButton -> field.value = v
                            is PDChoice -> field.value = listOf(v)
                            else -> Log.w(TAG, "Skipping unsupported field type: ${field.javaClass.simpleName}")
                        }
                        applied++
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not set ${field.fullyQualifiedName}: ${e.message}")
                    }
                }
                Log.i(TAG, "Applied $applied/${values.size} values")
                doc.save(output)
            }
            output
        } catch (e: Exception) {
            Log.e(TAG, "saveFilled failed", e)
            null
        }
    }

    // ---------- Helpers ----------

    private fun kindOf(field: PDField): Kind {
        return when (field) {
            is PDTextField -> Kind.TEXT
            is PDCheckBox -> Kind.CHECKBOX
            is PDRadioButton -> Kind.RADIO
            is PDChoice -> Kind.CHOICE
            else -> {
                val type = field.fieldType ?: return Kind.UNKNOWN
                when {
                    type.equals("Btn", ignoreCase = true) -> Kind.BUTTON
                    type.equals("Sig", ignoreCase = true) -> Kind.SIGNATURE
                    else -> Kind.UNKNOWN
                }
            }
        }
    }

    private fun optionsOf(field: PDField): List<String> = try {
        when (field) {
            is PDChoice -> field.options ?: emptyList()
            is PDRadioButton -> field.onValues?.toList() ?: emptyList()
            else -> emptyList()
        }
    } catch (e: Exception) { emptyList() }

    private fun valueOf(field: PDField): String = try {
        when (field) {
            is PDTextField -> field.value ?: ""
            is PDChoice -> field.value?.firstOrNull() ?: ""
            is PDRadioButton -> field.value ?: ""
            else -> ""
        }
    } catch (e: Exception) { "" }

    private fun checkedOf(field: PDField): Boolean = try {
        (field as? PDCheckBox)?.isChecked ?: false
    } catch (e: Exception) { false }
}
