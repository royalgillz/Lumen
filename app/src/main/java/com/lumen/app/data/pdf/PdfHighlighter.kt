package com.lumen.app.data.pdf

import android.graphics.RectF
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PdfHighlighter @Inject constructor() {

    data class PageHighlights(
        val rects: List<RectF>,
        val pageWidthPts: Float,
        val pageHeightPts: Float,
    )

    fun findOnPage(stream: InputStream, pageIndex: Int, keyword: String): PageHighlights {
        if (keyword.isBlank()) return PageHighlights(emptyList(), 0f, 0f)
        return try {
            PDDocument.load(stream).use { doc ->
                if (pageIndex >= doc.numberOfPages) return PageHighlights(emptyList(), 0f, 0f)
                val page = doc.getPage(pageIndex)

                // cropBox defines the visible/rendered area; fall back to mediaBox
                val box = page.cropBox ?: page.mediaBox
                val rotation = page.rotation % 360

                // PDFView renders pages accounting for rotation: swap dims for 90°/270°
                val pageWidthPts = if (rotation == 90 || rotation == 270) box.height else box.width
                val pageHeightPts = if (rotation == 90 || rotation == 270) box.width else box.height

                val stripper = KeywordStripper(
                    keyword = keyword.trim().lowercase(),
                    cropOffsetX = box.lowerLeftX,
                    cropOffsetY = box.lowerLeftY,
                )
                stripper.startPage = pageIndex + 1
                stripper.endPage = pageIndex + 1
                stripper.sortByPosition = true
                try { stripper.getText(doc) } catch (_: Exception) {}
                stripper.buildHighlights(pageHeightPts)

                PageHighlights(stripper.highlights, pageWidthPts, pageHeightPts)
            }
        } catch (_: Exception) {
            PageHighlights(emptyList(), 0f, 0f)
        }
    }
}

private class KeywordStripper(
    private val keyword: String,
    private val cropOffsetX: Float = 0f,
    private val cropOffsetY: Float = 0f,
) : PDFTextStripper() {

    private val collected = mutableListOf<TextPosition>()
    val highlights = mutableListOf<RectF>()

    override fun writeString(text: String, textPositions: List<TextPosition>) {
        super.writeString(text, textPositions)
        collected.addAll(textPositions)
    }

    fun buildHighlights(pageHeightPts: Float) {
        if (collected.isEmpty() || keyword.isEmpty()) return

        // Build a char→TextPosition-index map so ligature glyphs (fi, fl, ffi…) are handled
        // correctly. A single TextPosition can carry a 2-char unicode string, so character
        // indices in the assembled string don't map 1-to-1 with TextPosition indices.
        val charToPos = ArrayList<Int>(collected.size + 16)
        val fullText = buildString {
            collected.forEachIndexed { posIdx, tp ->
                val chars = tp.unicode ?: " "
                repeat(chars.length) { charToPos.add(posIdx) }
                append(chars)
            }
        }.lowercase()

        var searchFrom = 0
        while (true) {
            val idx = fullText.indexOf(keyword, searchFrom)
            if (idx < 0) break

            val charEnd = idx + keyword.length - 1
            if (charEnd >= charToPos.size) break

            val posStart = charToPos[idx]
            val posEnd = (charToPos[charEnd] + 1).coerceAtMost(collected.size)
            val slice = collected.subList(posStart, posEnd)

            if (slice.isNotEmpty()) {
                // Subtract cropBox origin so coordinates are relative to the rendered page
                val left = slice.minOf { it.x } - cropOffsetX
                val right = slice.maxOf { it.x + it.width } - cropOffsetX
                // PDF Y=0 at bottom; getY() is the baseline. Flip to screen space (Y=0 at top).
                val pdfYBaseline = slice.minOf { it.y } - cropOffsetY
                val pdfYTop = slice.maxOf { it.y + it.height } - cropOffsetY
                val screenTop = pageHeightPts - pdfYTop
                val screenBottom = pageHeightPts - pdfYBaseline
                highlights.add(RectF(left - 1f, screenTop, right + 1f, screenBottom))
            }

            searchFrom = (idx + keyword.length).coerceAtLeast(idx + 1)
        }
    }
}
