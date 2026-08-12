package com.lumen.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class QuantityTest {

    // @spec LIB-PLU-001
    @Test
    fun singularAtExactlyOne() {
        assertEquals("1 folder", quantity(1, "folder"))
        assertEquals("1 page", quantity(1, "page"))
        assertEquals("1 PDF", quantity(1, "PDF"))
        assertEquals("1 match", quantity(1, "match", "matches"))
    }

    // @spec LIB-PLU-001
    @Test
    fun pluralOtherwise() {
        assertEquals("0 folders", quantity(0, "folder"))
        assertEquals("3 folders", quantity(3, "folder"))
        assertEquals("312 PDFs", quantity(312, "PDF"))
        assertEquals("169 matches", quantity(169, "match", "matches"))
    }
}
