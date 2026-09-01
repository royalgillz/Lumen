package com.lumen.app.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchLaunchTest {

    // @spec SEARCH-ENTRY-002
    @Test
    fun capQuery_trimsSurroundingWhitespace() {
        assertEquals("visa F-1", SearchLaunch.capQuery("  visa F-1 \n"))
    }

    // @spec SEARCH-ENTRY-002
    @Test
    fun capQuery_capsAtMaxChars() {
        val long = "a".repeat(SearchLaunch.QUERY_MAX_CHARS + 100)
        assertEquals(SearchLaunch.QUERY_MAX_CHARS, SearchLaunch.capQuery(long).length)
    }

    // A selection at exactly the cap survives untouched.
    // @spec SEARCH-ENTRY-002
    @Test
    fun capQuery_keepsInputAtExactlyMaxChars() {
        val exact = "b".repeat(SearchLaunch.QUERY_MAX_CHARS)
        assertEquals(exact, SearchLaunch.capQuery(exact))
    }

    // A sender that supplied no text still opens search rather than dropping
    // the launch.
    // @spec SEARCH-ENTRY-002
    @Test
    fun capQuery_nullBecomesEmptyOpenSearchRequest() {
        assertEquals("", SearchLaunch.capQuery(null))
    }

    // @spec SEARCH-ENTRY-002
    @Test
    fun capQuery_whitespaceOnlyBecomesEmpty() {
        assertEquals("", SearchLaunch.capQuery("   \t "))
    }

    // Trim happens before the cap, so the cap never spends its budget on
    // leading whitespace.
    // @spec SEARCH-ENTRY-002
    @Test
    fun capQuery_trimsBeforeCapping() {
        val padded = " ".repeat(50) + "c".repeat(SearchLaunch.QUERY_MAX_CHARS)
        assertEquals("c".repeat(SearchLaunch.QUERY_MAX_CHARS), SearchLaunch.capQuery(padded))
    }
}
