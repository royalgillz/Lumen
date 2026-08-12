package com.lumen.app.data.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnippetBuilderTest {

    private val words = (1..100).joinToString(" ") { "word$it" }

    // @spec SEARCH-SNIP-001
    @Test
    fun windowsAroundFirstSpan_withEllipses() {
        // Highlight "word50" in a 100-word page: the snippet is a bounded window
        // around it, elided at both ends.
        val start = words.indexOf("word50")
        val span = start until start + "word50".length
        val snippet = SnippetBuilder.build(words, listOf(span))

        assertTrue(snippet.text.contains("word50"))
        assertTrue(snippet.text.startsWith("…"))
        assertTrue(snippet.text.endsWith("…"))
        // ~18-word window, allow slack for implementation rounding — but far
        // from the whole page.
        val wordCount = snippet.text.trim('…').trim().split(Regex("\\s+")).size
        assertTrue("window too large: $wordCount words", wordCount in 10..24)
    }

    // @spec SEARCH-SNIP-001
    @Test
    fun spanAtTextStart_noLeadingEllipsis() {
        val snippet = SnippetBuilder.build(words, listOf(0 until "word1".length))
        assertFalse(snippet.text.startsWith("…"))
        assertTrue(snippet.text.contains("word1"))
    }

    // @spec SEARCH-SNIP-002
    @Test
    fun outputIsPlainText_highlightRangesMapIntoSnippet() {
        val text = "a valid F-1 visa is required"
        val start = text.indexOf("F-1")
        val snippet = SnippetBuilder.build(text, listOf(start until start + 3))

        // No markup of any kind in the text — copy/share uses it verbatim.
        assertFalse(snippet.text.contains("<"))
        assertEquals(1, snippet.highlights.size)
        assertEquals("F-1", snippet.text.substring(snippet.highlights[0]))
    }

    // @spec SEARCH-SNIP-002
    @Test
    fun multipleSpansInsideWindow_allHighlighted() {
        val text = "the F-1 visa and F-1 status"
        val spans = Regex("F-1").findAll(text).map { it.range }.toList()
        val snippet = SnippetBuilder.build(text, spans)
        assertEquals(2, snippet.highlights.size)
        snippet.highlights.forEach { assertEquals("F-1", snippet.text.substring(it)) }
    }

    // @spec SEARCH-MATCH-004
    @Test
    fun noSpans_startOfPageWindow_noHighlights() {
        val snippet = SnippetBuilder.build(words, emptyList())
        assertTrue(snippet.text.startsWith("word1 "))
        assertTrue(snippet.highlights.isEmpty())
    }
}
