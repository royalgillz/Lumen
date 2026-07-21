package com.lumen.app.data.pdf

import android.content.Context
import android.graphics.RectF
import android.net.Uri
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.StructuredText
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PdfHighlighter @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    data class PageHighlights(
        val pageIndex: Int,
        val rects: List<RectF>,
        val pageWidthPts: Float,
        val pageHeightPts: Float,
    )

    /**
     * Compute highlight rectangles for [keyword] on [pageIndex] of the PDF at [uri].
     * Uses MuPDF's structured-text extractor; rects are in page-point coordinates
     * and already account for the page's rotation (MuPDF reports bboxes in the
     * page's rendered coordinate space).
     */
    suspend fun findOnPage(
        uri: Uri,
        pageIndex: Int,
        keyword: String,
        password: String? = null,
    ): PageHighlights {
        if (keyword.isBlank()) return empty(pageIndex)
        return withMuPdfDocument(context, uri, password) { doc ->
            extractForPage(doc, pageIndex, keyword)
        } ?: empty(pageIndex)
    }

    /**
     * Same extraction against an already-open [doc]. NOT gated — the caller must
     * hold the render permit and serialise document access (see
     * MuPdfPageRenderer.withDocumentGated), which is what makes this the cheap
     * path: no per-page document reopen and reparse.
     */
    fun findOnPageInDocument(doc: Document, pageIndex: Int, keyword: String): PageHighlights {
        if (keyword.isBlank()) return empty(pageIndex)
        return extractForPageLocked(doc, pageIndex, keyword)
    }

    // Gated: toStructuredText allocates native memory proportional to page complexity,
    // so it must share the single render permit with bitmap rasterisation.
    private suspend fun extractForPage(doc: Document, pageIndex: Int, keyword: String): PageHighlights =
        MuPdfGate.withRenderPermit { extractForPageLocked(doc, pageIndex, keyword) }

    private fun extractForPageLocked(doc: Document, pageIndex: Int, keyword: String): PageHighlights {
        if (pageIndex !in 0 until doc.countPages()) return empty(pageIndex)
        val page = runCatching { doc.loadPage(pageIndex) }.getOrNull() ?: return empty(pageIndex)
        return try {
            val bounds = page.bounds
            val pageWidthPts = bounds.x1 - bounds.x0
            val pageHeightPts = bounds.y1 - bounds.y0
            val stext = runCatching { page.toStructuredText("preserve-whitespace") }.getOrNull()
                ?: return PageHighlights(pageIndex, emptyList(), pageWidthPts, pageHeightPts)
            try {
                val rects = extractKeywordRects(stext, keyword, bounds.x0, bounds.y0)
                PageHighlights(pageIndex, rects, pageWidthPts, pageHeightPts)
            } finally {
                runCatching { stext.destroy() }
            }
        } catch (_: OutOfMemoryError) {
            empty(pageIndex)
        } catch (_: Throwable) {
            empty(pageIndex)
        } finally {
            runCatching { page.destroy() }
        }
    }

    private fun empty(pageIndex: Int) = PageHighlights(pageIndex, emptyList(), 0f, 0f)

    /**
     * Walks the structured-text tree once, building a parallel buffer of
     * lowercase code points and per-code-unit [Quad] references so a substring
     * match in the buffer can be mapped back to the union of its source char
     * quads.
     *
     * Multi-word queries are matched per token: search results come from an
     * AND-of-tokens FTS query, so "climate change" must light up every "climate"
     * and every "change" on the page — an exact-phrase match would often find
     * nothing on a page FTS legitimately matched. Matches are returned in
     * reading order across all tokens; a match fully contained inside another
     * (from substring-of-each-other tokens) is dropped.
     *
     * Handles ligature glyphs (fi, fl, ffi…) and supplementary characters
     * (surrogate pairs) by recording one quad per UTF-16 code unit produced.
     */
    private fun extractKeywordRects(
        stext: StructuredText,
        keyword: String,
        offsetX: Float,
        offsetY: Float,
    ): List<RectF> {
        val needles = keyword.trim()
            .split(Regex("\\s+"))
            .map { buildLowercaseString(it) }
            .filter { it.isNotEmpty() }
            .distinct()
        if (needles.isEmpty()) return emptyList()

        val buffer = StringBuilder()
        val quads = ArrayList<Quad>(256)

        val blocks = runCatching { stext.blocks }.getOrNull() ?: return emptyList()
        for (block in blocks) {
            if (block == null) continue
            val lines = runCatching { block.lines }.getOrNull() ?: continue
            for (line in lines) {
                if (line == null) continue
                val chars = runCatching { line.chars }.getOrNull() ?: continue
                for (ch in chars) {
                    if (ch == null) continue
                    val cp = ch.c
                    val quad = ch.quad ?: continue
                    val lower = Character.toLowerCase(cp)
                    buffer.appendCodePoint(lower)
                    repeat(Character.charCount(lower)) { quads.add(quad) }
                }
                // Insert a synthetic space between lines so cross-line matches
                // don't glue end-of-line words together. Quads list grows in
                // parallel so the index alignment is preserved.
                buffer.append(' ')
                quads.add(SENTINEL_QUAD)
            }
        }

        val haystack = buffer.toString()
        if (haystack.isEmpty()) return emptyList()

        // Collect every token's matches as [start, endInclusive] index ranges.
        val matches = ArrayList<IntRange>()
        for (needle in needles) {
            if (haystack.length < needle.length) continue
            var searchFrom = 0
            while (true) {
                val idx = haystack.indexOf(needle, searchFrom)
                if (idx < 0) break
                val end = idx + needle.length - 1
                if (end < quads.size) matches.add(idx..end)
                searchFrom = idx + needle.length
            }
        }
        if (matches.isEmpty()) return emptyList()

        // Reading order; longest-first at equal starts so containment is caught
        // by a single max-end sweep.
        matches.sortWith(compareBy({ it.first }, { -it.last }))
        val rects = ArrayList<RectF>(matches.size)
        var maxEnd = -1
        for (m in matches) {
            if (m.last <= maxEnd) continue
            maxEnd = m.last
            rects.add(unionRect(quads, m.first, m.last, offsetX, offsetY))
        }
        return rects
    }

    private fun unionRect(
        quads: List<Quad>,
        start: Int,
        endInclusive: Int,
        offsetX: Float,
        offsetY: Float,
    ): RectF {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (i in start..endInclusive) {
            val q = quads[i]
            if (q === SENTINEL_QUAD) continue
            val xs = floatArrayOf(q.ul_x, q.ur_x, q.ll_x, q.lr_x)
            val ys = floatArrayOf(q.ul_y, q.ur_y, q.ll_y, q.lr_y)
            for (x in xs) { if (x < minX) minX = x; if (x > maxX) maxX = x }
            for (y in ys) { if (y < minY) minY = y; if (y > maxY) maxY = y }
        }
        // 1pt padding on the horizontal axis mirrors the pre-MuPDF rect inflation
        // so the amber highlight visibly overhangs the glyph edges.
        return RectF(
            minX - 1f - offsetX,
            minY - offsetY,
            maxX + 1f - offsetX,
            maxY - offsetY,
        )
    }

    private fun buildLowercaseString(s: String): String {
        val sb = StringBuilder(s.length)
        val trimmed = s.trim()
        var i = 0
        while (i < trimmed.length) {
            val cp = trimmed.codePointAt(i)
            sb.appendCodePoint(Character.toLowerCase(cp))
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    private companion object {
        // Marker Quad inserted between lines so index alignment is preserved
        // without contributing to keyword-spanning rect unions.
        val SENTINEL_QUAD = Quad(
            0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
        )
    }
}
