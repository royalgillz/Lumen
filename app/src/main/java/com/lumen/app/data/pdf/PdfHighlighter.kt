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
                val pageWidthPts = page.mediaBox.width
                val pageHeightPts = page.mediaBox.height

                val stripper = KeywordStripper(keyword.trim().lowercase())
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

private class KeywordStripper(private val keyword: String) : PDFTextStripper() {

    private val collected = mutableListOf<TextPosition>()
    val highlights = mutableListOf<RectF>()

    override fun writeString(text: String, textPositions: List<TextPosition>) {
        super.writeString(text, textPositions)
        collected.addAll(textPositions)
    }

    fun buildHighlights(pageHeightPts: Float) {
        if (collected.isEmpty() || keyword.isEmpty()) return

        // Build full text from collected positions (preserves 1-to-1 index mapping)
        val fullText = collected.joinToString("") { it.unicode ?: " " }.lowercase()

        var searchFrom = 0
        while (true) {
            val idx = fullText.indexOf(keyword, searchFrom)
            if (idx < 0) break
            val end = (idx + keyword.length).coerceAtMost(collected.size)
            if (idx >= end) break

            val slice = collected.subList(idx, end)
            if (slice.isNotEmpty()) {
                val left = slice.minOf { it.x }
                val right = slice.maxOf { it.x + it.width }
                // PDF y=0 at bottom; TextPosition.y is the baseline from bottom.
                // Character box: bottom = y, top = y + height.
                // Convert to screen space (y=0 at top):
                val pdfYBottom = slice.minOf { it.y }
                val pdfYTop = slice.maxOf { it.y + it.height }
                val screenTop = pageHeightPts - pdfYTop
                val screenBottom = pageHeightPts - pdfYBottom
                highlights.add(RectF(left - 1f, screenTop, right + 1f, screenBottom))
            }

            searchFrom = (idx + keyword.length).coerceAtLeast(idx + 1)
        }
    }
}
