package com.lumen.app.data.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineExtractorTest {

    private val extractor = LineExtractor()

    @Test
    fun emptyInput_returnsEmptyList() {
        assertTrue(extractor.extract("").isEmpty())
    }

    @Test
    fun shortLines_filteredOut() {
        assertTrue(extractor.extract("ab\nxy\n").isEmpty())
    }

    @Test
    fun normalLines_returned() {
        val result = extractor.extract("Hello world\nThis is a test")
        assertEquals(listOf("Hello world", "This is a test"), result)
    }

    @Test
    fun linesAreTrimmed() {
        val result = extractor.extract("  Hello world  \n  test line  ")
        assertEquals(listOf("Hello world", "test line"), result)
    }

    @Test
    fun blankLines_filteredOut() {
        val result = extractor.extract("Hello\n\n\nWorld!")
        assertEquals(listOf("Hello", "World!"), result)
    }

    @Test
    fun mixedShortAndLong() {
        val result = extractor.extract("ab\nHello world\nxy\nGoodbye world")
        assertEquals(listOf("Hello world", "Goodbye world"), result)
    }

    @Test
    fun exactlyMinLength_included() {
        val result = extractor.extract("abc")
        assertEquals(listOf("abc"), result)
    }

    @Test
    fun twoChars_filteredOut() {
        assertTrue(extractor.extract("ab").isEmpty())
    }
}
