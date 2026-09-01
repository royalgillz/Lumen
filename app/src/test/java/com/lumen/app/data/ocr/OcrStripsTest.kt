package com.lumen.app.data.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrStripsTest {

    private val scale = 120f / 72f // the OCR render scale

    // @spec LIB-BIG-001
    @Test
    fun needsStrips_onlyForExtremeAspectPagesOverTheBudget() {
        assertFalse(OcrStrips.needsStrips(595f, 842f, scale))              // A4
        assertFalse(OcrStrips.needsStrips(612f, 1500f, scale))             // tall-ish, under 3:1
        assertFalse(OcrStrips.needsStrips(612f, 2000f, 0.5f))              // over 3:1 but fits the budget
        assertTrue(OcrStrips.needsStrips(612f, 15000f, scale))             // GoodNotes-style
    }

    // Bands tile the page exactly; render windows stay inside the page and
    // inside the strip pixel budget.
    // @spec LIB-BIG-001
    @Test
    fun bands_tileExactlyAndRespectBudget() {
        val pageH = 15000f
        val bands = OcrStrips.bands(pageH, scale)
        assertTrue(bands.size > 1)
        assertEquals(0f, bands.first().bandTopPt, 1e-3f)
        assertEquals(pageH, bands.last().bandBottomPt, 1e-3f)
        assertTrue(bands.last().isLast)
        for (i in 1 until bands.size) {
            assertEquals(bands[i - 1].bandBottomPt, bands[i].bandTopPt, 1e-3f)
            assertFalse(bands[i - 1].isLast)
        }
        for (b in bands) {
            assertTrue(b.renderTopPt <= b.bandTopPt)
            assertTrue(b.renderBottomPt >= b.bandBottomPt)
            assertTrue(b.renderTopPt >= 0f)
            assertTrue(b.renderBottomPt <= pageH)
            assertTrue((b.renderBottomPt - b.renderTopPt) * scale <= OcrStrips.MAX_STRIP_PX + 1f)
        }
        // Interior bands carry the full overlap on both sides.
        val mid = bands[1]
        assertEquals(mid.bandTopPt - OcrStrips.OVERLAP_PT, mid.renderTopPt, 1e-3f)
        assertEquals(mid.bandBottomPt + OcrStrips.OVERLAP_PT, mid.renderBottomPt, 1e-3f)
    }

    // The seam rule: every word center is owned by exactly one band, and the
    // page's exact bottom edge belongs to the last band.
    // @spec LIB-BIG-001
    @Test
    fun keepsWord_everyCenterOwnedExactlyOnce() {
        val pageH = 15000f
        val bands = OcrStrips.bands(pageH, scale)
        val centers = floatArrayOf(0f, 1f, 2811.9f, 2812f, 7500f, 14999f, pageH)
        for (c in centers) {
            assertEquals("center $c", 1, bands.count { OcrStrips.keepsWord(it, c) })
        }
        // A band boundary belongs to the band it opens, not the one it closes.
        val boundary = bands[0].bandBottomPt
        assertFalse(OcrStrips.keepsWord(bands[0], boundary))
        assertTrue(OcrStrips.keepsWord(bands[1], boundary))
    }

    // @spec LIB-BIG-001
    @Test
    fun assembleText_breaksLinesOnVerticalGaps() {
        val words = listOf(
            Triple("hello", 0f, 20f),
            Triple("world", 2f, 21f),    // same line — top above prev bottom
            Triple("next", 30f, 50f),    // starts below prev bottom — new line
        )
        assertEquals("hello world\nnext", OcrStrips.assembleText(words))
        assertEquals("", OcrStrips.assembleText(emptyList()))
    }
}
