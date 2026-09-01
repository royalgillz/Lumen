package com.lumen.app.data.ocr

import kotlin.math.ceil

/**
 * Pure math for strip-based OCR of extreme-aspect pages (GoodNotes-style
 * exports: one page, many thousands of points tall). Rendering such a page
 * whole at OCR resolution allocates a bitmap in the hundred-MB class and
 * fails; instead the page is rendered as full-width vertical strips, each
 * within the bitmap budget, OCR'd independently, and re-assembled.
 *
 * Bands tile the page height exactly (no gaps, no overlap); each band's
 * RENDER window extends [OVERLAP_PT] beyond the band on both sides so a text
 * line cut by a band boundary is fully visible in the strip that owns it.
 * The seam rule dedupes the overlap: a recognized word belongs to the band
 * containing its vertical CENTER — every word is kept by exactly one strip,
 * and a word whose height is at most 2x[OVERLAP_PT] is guaranteed fully
 * rendered in its owning strip.
 */
// @spec LIB-BIG-001
object OcrStrips {

    /** A page qualifies for strips only past this height:width ratio. */
    const val ASPECT_THRESHOLD = 3f

    /** Render overlap beyond each band edge, in page points (~2 text lines). */
    const val OVERLAP_PT = 24f

    /** Tallest strip bitmap the OCR path may allocate, in pixels. At the
     *  default 120 dpi over a US-Letter width this is a ~16 MB bitmap. */
    const val MAX_STRIP_PX = 4000

    /**
     * One strip: [bandTopPt, bandBottomPt) is the exclusive ownership band,
     * [renderTopPt, renderBottomPt) the window actually rasterised.
     */
    data class Band(
        val bandTopPt: Float,
        val bandBottomPt: Float,
        val renderTopPt: Float,
        val renderBottomPt: Float,
        val isLast: Boolean,
    )

    fun needsStrips(pageWPt: Float, pageHPt: Float, scale: Float, maxStripPx: Int = MAX_STRIP_PX): Boolean {
        if (pageWPt <= 0f || pageHPt <= 0f || scale <= 0f) return false
        return pageHPt > pageWPt * ASPECT_THRESHOLD && pageHPt * scale > maxStripPx
    }

    /**
     * Equal-height bands tiling [0, pageHPt), each render window (band plus
     * overlap on both sides, clamped to the page) at most [maxStripPx] tall
     * at [scale].
     */
    fun bands(
        pageHPt: Float,
        scale: Float,
        maxStripPx: Int = MAX_STRIP_PX,
        overlapPt: Float = OVERLAP_PT,
    ): List<Band> {
        if (pageHPt <= 0f || scale <= 0f) return emptyList()
        val maxRenderPt = maxStripPx / scale
        // Band body budget after both overlaps; a degenerate budget (huge
        // scale) still advances at least one point per band.
        val bodyBudget = (maxRenderPt - 2f * overlapPt).coerceAtLeast(1f)
        val count = ceil((pageHPt / bodyBudget).toDouble()).toInt().coerceAtLeast(1)
        val bodyPt = pageHPt / count
        return List(count) { i ->
            val top = i * bodyPt
            val bottom = if (i == count - 1) pageHPt else (i + 1) * bodyPt
            Band(
                bandTopPt = top,
                bandBottomPt = bottom,
                renderTopPt = (top - overlapPt).coerceAtLeast(0f),
                renderBottomPt = (bottom + overlapPt).coerceAtMost(pageHPt),
                isLast = i == count - 1,
            )
        }
    }

    /** The seam rule: this band keeps a word whose vertical center (page pt)
     *  falls inside its exclusive band. The last band also owns the exact
     *  bottom edge. */
    fun keepsWord(band: Band, centerYPt: Float): Boolean =
        centerYPt >= band.bandTopPt &&
            (centerYPt < band.bandBottomPt || (band.isLast && centerYPt <= band.bandBottomPt))

    /**
     * Rebuild page text from kept words accumulated across strips in order.
     * Words are (text, topPx, bottomPx) in full-page pixels; a line break is
     * inserted when a word starts below the previous word's bottom — enough
     * layout for FTS and snippets, which is all OCR text feeds.
     */
    fun assembleText(words: List<Triple<String, Float, Float>>): String {
        if (words.isEmpty()) return ""
        val sb = StringBuilder()
        var prevBottom = Float.NEGATIVE_INFINITY
        for ((text, top, bottom) in words) {
            if (sb.isNotEmpty()) {
                sb.append(if (top >= prevBottom) '\n' else ' ')
            }
            sb.append(text)
            prevBottom = bottom
        }
        return sb.toString()
    }
}
