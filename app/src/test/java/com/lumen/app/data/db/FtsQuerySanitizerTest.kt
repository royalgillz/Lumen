package com.lumen.app.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

class FtsQuerySanitizerTest {

    @Test
    fun multiWord_andWithPrefixWildcard() {
        assertEquals("hello* AND world*", FtsQuerySanitizer.sanitize("hello world"))
    }

    @Test
    fun doubleQuotes_stripped() {
        assertEquals("hello* AND world*", FtsQuerySanitizer.sanitize("hello \"world\""))
    }

    @Test
    fun asterisks_stripped() {
        assertEquals("hello* AND world*", FtsQuerySanitizer.sanitize("hello* world*"))
    }

    @Test
    fun leadingTrailingSpaces_trimmed() {
        assertEquals("hello*", FtsQuerySanitizer.sanitize("  hello  "))
    }

    @Test
    fun mixedSpecialChars_allStripped() {
        assertEquals("hello* AND world*", FtsQuerySanitizer.sanitize("\"hello*\" world*"))
    }

    @Test
    fun singleWord_prefixWildcard() {
        assertEquals("kotlin*", FtsQuerySanitizer.sanitize("kotlin"))
    }

    @Test
    fun threeWords_andChained() {
        assertEquals("a* AND b* AND c*", FtsQuerySanitizer.sanitize("a b c"))
    }
}
