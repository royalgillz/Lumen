package com.lumen.app.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

class FtsQuerySanitizerTest {

    @Test
    fun multiWord_spaceJoinedWithPrefixWildcard() {
        // Space = implicit AND in both standard and enhanced FTS4 query syntax.
        // " AND " would be a literal term under the standard syntax.
        assertEquals("hello* world*", FtsQuerySanitizer.sanitize("hello world"))
    }

    @Test
    fun doubleQuotes_stripped() {
        assertEquals("hello* world*", FtsQuerySanitizer.sanitize("hello \"world\""))
    }

    @Test
    fun asterisks_stripped() {
        assertEquals("hello* world*", FtsQuerySanitizer.sanitize("hello* world*"))
    }

    @Test
    fun leadingTrailingSpaces_trimmed() {
        assertEquals("hello*", FtsQuerySanitizer.sanitize("  hello  "))
    }

    @Test
    fun mixedSpecialChars_allStripped() {
        assertEquals("hello* world*", FtsQuerySanitizer.sanitize("\"hello*\" world*"))
    }

    @Test
    fun singleWord_prefixWildcard() {
        assertEquals("kotlin*", FtsQuerySanitizer.sanitize("kotlin"))
    }

    @Test
    fun threeWords_spaceChained() {
        assertEquals("a* b* c*", FtsQuerySanitizer.sanitize("a b c"))
    }
}
