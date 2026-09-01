package com.lumen.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading-progress line for library and recents rows: saved page is 0-indexed
 * and displays as page + 1 (project convention), the percent is floored and
 * capped at 100, and documents without a usable page count show nothing.
 */
class ReadingProgressLabelTest {

    // @spec LIB-PRG-001
    @Test
    fun label_displaysPagePlusOneAndFlooredPercent() {
        // Saved page 33 (0-indexed) = shown page 34; 34*100/120 floors to 28.
        assertEquals("p. 34 of 120 · 28%", readingProgressLabel(33, 120))
    }

    // @spec LIB-PRG-001
    @Test
    fun label_lastPageOfDocumentReads100Percent() {
        assertEquals("p. 120 of 120 · 100%", readingProgressLabel(119, 120))
        assertEquals("p. 1 of 1 · 100%", readingProgressLabel(0, 1))
    }

    // A re-index can shrink a document past a stale saved position — the shown
    // page clamps to the current count, which also caps the percent at 100.
    // @spec LIB-PRG-001
    @Test
    fun label_clampsStalePositionsBeyondTheCurrentPageCount() {
        assertEquals("p. 120 of 120 · 100%", readingProgressLabel(500, 120))
    }

    // @spec LIB-PRG-001
    @Test
    fun label_isNullWithoutAUsablePageCount() {
        assertNull(readingProgressLabel(3, 0))
        assertNull(readingProgressLabel(3, -1))
    }

    // @spec LIB-PRG-001
    @Test
    fun label_isNullForANegativeSavedPage() {
        assertNull(readingProgressLabel(-1, 120))
    }

    // @spec LIB-PRG-001
    @Test
    fun label_firstPageOfALongDocumentFloorsToZeroPercent() {
        assertEquals("p. 1 of 120 · 0%", readingProgressLabel(0, 120))
    }
}
