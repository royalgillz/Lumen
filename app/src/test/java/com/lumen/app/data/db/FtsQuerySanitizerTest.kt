package com.lumen.app.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

class FtsQuerySanitizerTest {

    @Test
    fun normalInput_wrappedInQuotes() {
        assertEquals("\"hello world\"", FtsQuerySanitizer.sanitize("hello world"))
    }

    @Test
    fun doubleQuotes_stripped() {
        assertEquals("\"hello world\"", FtsQuerySanitizer.sanitize("hello \"world\""))
    }

    @Test
    fun asterisks_stripped() {
        assertEquals("\"hello world\"", FtsQuerySanitizer.sanitize("hello* world*"))
    }

    @Test
    fun leadingTrailingSpaces_trimmed() {
        assertEquals("\"hello\"", FtsQuerySanitizer.sanitize("  hello  "))
    }

    @Test
    fun mixedSpecialChars_allStripped() {
        assertEquals("\"hello world\"", FtsQuerySanitizer.sanitize("\"hello*\" world*"))
    }

    @Test
    fun singleWord_wrappedInQuotes() {
        assertEquals("\"kotlin\"", FtsQuerySanitizer.sanitize("kotlin"))
    }
}
