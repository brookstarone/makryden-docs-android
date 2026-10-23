package com.makryden.docs

import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject

/**
 * Data model for PDF annotations. All coordinates are normalized 0..1
 * relative to the page dimensions, so they survive zoom/render changes.
 */
object PdfAnnotations {

    enum class Tool {
        NONE, HIGHLIGHT, UNDERLINE, TEXT_NOTE, RECTANGLE, FREEHAND, SIGNATURE
    }

    data class Stroke(
        val tool: Tool,
        val pageIndex: Int,
        // For rect-based tools: the bounding rect. For freehand: bounding + points.
        val rect: RectF,
        val points: List<Pair<Float, Float>> = emptyList(), // normalized, for freehand/signature
        val text: String = "", // for text notes
        val color: Int = 0xFFE8C266.toInt(), // gold default
        val strokeWidth: Float = 3f // dp-ish, for freehand/rect outlines
    )

    /** Serialize a list of strokes to JSON. */
    fun toJson(strokes: List<Stroke>): String {
        val arr = JSONArray()
        for (s in strokes) {
            val obj = JSONObject()
            obj.put("tool", s.tool.name)
            obj.put("page", s.pageIndex)
            obj.put("l", s.rect.left.toDouble())
            obj.put("t", s.rect.top.toDouble())
            obj.put("r", s.rect.right.toDouble())
            obj.put("b", s.rect.bottom.toDouble())
            obj.put("text", s.text)
            obj.put("color", s.color)
            obj.put("w", s.strokeWidth.toDouble())
            if (s.points.isNotEmpty()) {
                val pts = JSONArray()
                for ((x, y) in s.points) {
                    val p = JSONArray()
                    p.put(x.toDouble())
                    p.put(y.toDouble())
                    pts.put(p)
                }
                obj.put("pts", pts)
            }
            arr.put(obj)
        }
        return arr.toString()
    }

    /** Deserialize JSON back to strokes. */
    fun fromJson(json: String): List<Stroke> {
        val result = mutableListOf<Stroke>()
        if (json.isBlank()) return result
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val tool = try {
                    Tool.valueOf(obj.optString("tool", "HIGHLIGHT"))
                } catch (e: Exception) { Tool.HIGHLIGHT }
                val rect = RectF(
                    obj.optDouble("l", 0.0).toFloat(),
                    obj.optDouble("t", 0.0).toFloat(),
                    obj.optDouble("r", 0.0).toFloat(),
                    obj.optDouble("b", 0.0).toFloat()
                )
                val points = mutableListOf<Pair<Float, Float>>()
                val ptsArr = obj.optJSONArray("pts")
                if (ptsArr != null) {
                    for (j in 0 until ptsArr.length()) {
                        val p = ptsArr.getJSONArray(j)
                        points.add(Pair(p.optDouble(0, 0.0).toFloat(), p.optDouble(1, 0.0).toFloat()))
                    }
                }
                result.add(Stroke(
                    tool = tool,
                    pageIndex = obj.optInt("page", 0),
                    rect = rect,
                    points = points,
                    text = obj.optString("text", ""),
                    color = obj.optInt("color", 0xFFE8C266.toInt()),
                    strokeWidth = obj.optDouble("w", 3.0).toFloat()
                ))
            }
        } catch (e: Exception) {
            // Return what we have
        }
        return result
    }
}
