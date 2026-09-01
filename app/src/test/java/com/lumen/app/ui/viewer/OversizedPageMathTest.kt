package com.lumen.app.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OversizedPageMathTest {

    private val maxDim = 4096f
    private val maxBytes = 32 * 1024 * 1024

    // A GoodNotes-style export: one US-Letter-wide page, ~20x as tall.
    private val longW = 612f
    private val longH = 15000f

    @Test
    fun cappedScale_leavesNormalPagesAlone() {
        assertEquals(1.76f, OversizedPageMath.cappedScale(595f, 842f, 1.76f, maxDim, maxBytes), 1e-4f)
    }

    // @spec VIEW-BIG-001
    @Test
    fun cappedScale_dimCapBindsOnLongPages() {
        val capped = OversizedPageMath.cappedScale(longW, longH, 1.76f, maxDim, maxBytes)
        assertEquals(maxDim / longH, capped, 1e-4f)
        assertTrue(capped < 0.3f)
    }

    // @spec VIEW-BIG-001
    @Test
    fun isOversized_onlyForMeaningfullyDegradedPages() {
        assertTrue(OversizedPageMath.isOversized(longW, longH, 1.76f, maxDim, maxBytes))
        assertFalse(OversizedPageMath.isOversized(595f, 842f, 1.76f, maxDim, maxBytes))
        // Thumbnail-scale render of the long page fits the caps — no region path.
        assertFalse(OversizedPageMath.isOversized(longW, longH, 0.2f, maxDim, maxBytes))
    }

    // @spec VIEW-BIG-001
    @Test
    fun regionForViewport_addsMarginsAndClampsToPage() {
        val visible = PageRegion(0f, 1000f, 612f, 2000f)
        val r = OversizedPageMath.regionForViewport(longW, longH, visible, 1.76f, maxBytes)
        assertEquals(0f, r.left, 1e-3f)          // clamped at page edge
        assertEquals(612f, r.right, 1e-3f)
        assertEquals(500f, r.top, 1e-3f)         // 1000 - 0.5*1000
        assertEquals(2500f, r.bottom, 1e-3f)     // 2000 + 0.5*1000
        assertTrue(OversizedPageMath.covers(r, visible))
    }

    @Test
    fun regionForViewport_topOfPageClampsMarginAbove() {
        val visible = PageRegion(0f, 0f, 612f, 1000f)
        val r = OversizedPageMath.regionForViewport(longW, longH, visible, 1.76f, maxBytes)
        assertEquals(0f, r.top, 1e-3f)
        assertEquals(1500f, r.bottom, 1e-3f)
    }

    // A budget too small even for the bare viewport center-crops rather than fails.
    // @spec VIEW-BIG-001
    @Test
    fun regionForViewport_tinyBudgetCenterCrops() {
        val visible = PageRegion(0f, 1000f, 612f, 2000f)
        val tinyBudget = 1 * 1024 * 1024 // 262144 px
        val r = OversizedPageMath.regionForViewport(longW, longH, visible, 1f, tinyBudget)
        assertFalse(r.isEmpty)
        val px = r.width * r.height
        assertTrue(px <= tinyBudget / 4f + 1f)
        // Still centered on what the user is looking at.
        assertEquals(1500f, (r.top + r.bottom) / 2f, 1f)
        assertEquals(306f, (r.left + r.right) / 2f, 1f)
    }

    @Test
    fun covers_respectsContainmentWithSlack() {
        val rendered = PageRegion(0f, 500f, 612f, 2500f)
        assertTrue(OversizedPageMath.covers(rendered, PageRegion(0f, 500f, 612f, 2500f)))
        assertTrue(OversizedPageMath.covers(rendered, PageRegion(10f, 600f, 600f, 2400f)))
        assertFalse(OversizedPageMath.covers(rendered, PageRegion(0f, 400f, 612f, 2400f)))
        assertFalse(OversizedPageMath.covers(rendered, PageRegion(0f, 600f, 612f, 2600f)))
    }
}
