package com.makryden.docs

import android.graphics.Typeface
import android.text.Html
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.AbsoluteSizeSpan
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.os.Build
import android.text.Layout

/**
 * Converts between Spannable (native editor) and HTML (Supabase storage).
 * Keeps compatibility with the web Writer which stores content_html.
 * Pure ASCII - no special chars.
 */
object HtmlConverter {

    /** Convert editor Spannable to HTML for Supabase. */
    fun toHtml(text: Spannable): String {
        val sb = StringBuilder()
        val len = text.length
        var i = 0
        var inParagraph = false

        // Track block-level state per paragraph
        val lines = text.toString().split("\n")
        var offset = 0
        for ((lineIndex, line) in lines.withIndex()) {
            val lineStart = offset
            val lineEnd = offset + line.length
            val isLast = lineIndex == lines.size - 1

            // Determine block type from spans covering this line
            val alignSpans = text.getSpans(lineStart, lineEnd, AlignmentSpan.Standard::class.java)
            val align = alignSpans.firstOrNull()?.alignment
            val sizeSpans = text.getSpans(lineStart, lineEnd, AbsoluteSizeSpan::class.java)
            val headingLevel = when (sizeSpans.firstOrNull()?.size) {
                32 -> 1
                26 -> 2
                22 -> 3
                else -> 0
            }

            val alignAttr = when (align) {
                Layout.Alignment.ALIGN_CENTER -> " style=\"text-align:center\""
                Layout.Alignment.ALIGN_OPPOSITE -> " style=\"text-align:right\""
                else -> ""
            }

            val tag = when (headingLevel) {
                1 -> "h1"
                2 -> "h2"
                3 -> "h3"
                else -> "p"
            }

            sb.append("<$tag$alignAttr>")
            appendInlineHtml(sb, text, lineStart, lineEnd)
            sb.append("</$tag>")

            offset = lineEnd + 1 // skip the \n
            if (!isLast) {
                // newlines between blocks are structural, not <br>
            }
        }
        return sb.toString()
    }

    private fun appendInlineHtml(sb: StringBuilder, text: Spannable, start: Int, end: Int) {
        var i = start
        while (i < end) {
            // Find next span boundary
            var next = end
            val spans = text.getSpans(i, end, Any::class.java)
            for (span in spans) {
                val s = text.getSpanStart(span)
                val e = text.getSpanEnd(span)
                if (s > i && s < next) next = s
                if (e > i && e < next) next = e
            }

            // Open tags for spans starting at i
            val opening = mutableListOf<String>()
            val closing = mutableListOf<String>()
            for (span in text.getSpans(i, next, Any::class.java)) {
                if (text.getSpanStart(span) == i) {
                    when (span) {
                        is StyleSpan -> {
                            when (span.style) {
                                Typeface.BOLD -> { opening.add("<b>"); closing.add(0, "</b>") }
                                Typeface.ITALIC -> { opening.add("<i>"); closing.add(0, "</i>") }
                                Typeface.BOLD_ITALIC -> {
                                    opening.add("<b><i>")
                                    closing.add(0, "</i></b>")
                                }
                            }
                        }
                        is UnderlineSpan -> { opening.add("<u>"); closing.add(0, "</u>") }
                        is StrikethroughSpan -> { opening.add("<s>"); closing.add(0, "</s>") }
                        is ForegroundColorSpan -> {
                            val hex = String.format("#%06X", 0xFFFFFF and span.foregroundColor)
                            opening.add("<font color=\"$hex\">")
                            closing.add(0, "</font>")
                        }
                        is BackgroundColorSpan -> {
                            val hex = String.format("#%06X", 0xFFFFFF and span.backgroundColor)
                            opening.add("<mark style=\"background:$hex\">")
                            closing.add(0, "</mark>")
                        }
                    }
                }
            }

            for (tag in opening) sb.append(tag)
            // Escape HTML in text content
            val chunk = text.substring(i, next)
            sb.append(escapeHtml(chunk))
            for (tag in closing) sb.append(tag)

            i = next
        }
    }

    private fun escapeHtml(s: String): String {
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    /** Convert HTML from Supabase to Spannable for the editor. */
    fun fromHtml(html: String): SpannableStringBuilder {
        if (html.isEmpty()) return SpannableStringBuilder("")
        return try {
            val spanned = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
            } else {
                @Suppress("DEPRECATION")
                Html.fromHtml(html)
            }
            SpannableStringBuilder(spanned)
        } catch (e: Exception) {
            SpannableStringBuilder(html.replace(Regex("<[^>]*>"), ""))
        }
    }
}
