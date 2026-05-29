package com.lumen.app.data.ocr

import org.json.JSONArray
import org.json.JSONObject

/** A word and its bounding box as fractions (0..1) of the page's width/height. */
data class NormWord(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * Serialises OCR word boxes for storage on a page row, normalised to the page so the
 * data is independent of the OCR render resolution. The viewer multiplies these by
 * the page's point dimensions at draw time, so highlights line up regardless of the
 * zoom or DPI used during indexing.
 */
object OcrWordBoxes {

    fun encode(words: List<OcrWord>, bitmapWidth: Int, bitmapHeight: Int): String? {
        if (words.isEmpty() || bitmapWidth <= 0 || bitmapHeight <= 0) return null
        val w = bitmapWidth.toFloat()
        val h = bitmapHeight.toFloat()
        val arr = JSONArray()
        for (word in words) {
            arr.put(
                JSONObject().apply {
                    put("s", word.text)
                    put("l", (word.box.left / w).toDouble())
                    put("t", (word.box.top / h).toDouble())
                    put("r", (word.box.right / w).toDouble())
                    put("b", (word.box.bottom / h).toDouble())
                }
            )
        }
        return arr.toString()
    }

    fun decode(json: String?): List<NormWord> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        NormWord(
                            text = o.optString("s"),
                            left = o.optDouble("l").toFloat(),
                            top = o.optDouble("t").toFloat(),
                            right = o.optDouble("r").toFloat(),
                            bottom = o.optDouble("b").toFloat(),
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
