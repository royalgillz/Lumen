package com.lumen.app.data.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NormalizedMatcherTest {

    private fun spansAsText(original: String, query: String): List<String> =
        NormalizedMatcher.findMatches(original, query).map { original.substring(it) }

    // @spec SEARCH-MATCH-001, SEARCH-MATCH-003
    @Test
    fun punctuatedOccurrence_matchedByCompactQuery_spanIncludesPunctuation() {
        assertEquals(listOf("F-1"), spansAsText("a valid F-1 visa", "F1"))
        assertEquals(listOf("F.1"), spansAsText("see section F.1 below", "f1"))
    }

    // @spec SEARCH-MATCH-001
    @Test
    fun compactOccurrence_matchedByPunctuatedQuery() {
        assertEquals(listOf("F1"), spansAsText("an F1 student", "f-1"))
    }

    // @spec SEARCH-MATCH-001
    @Test
    fun tokenStartAnchoring_noMidTokenMatches() {
        // "1" must not match inside "21"; it does match the token-start "1" of "1.2".
        assertEquals(emptyList<String>(), spansAsText("21 chairs", "1"))
        assertEquals(listOf("1"), spansAsText("version 1.2 shipped", "1"))
    }

    // @spec SEARCH-MATCH-001
    @Test
    fun prefixExtension_matchesTokenPrefixOnly() {
        // Query "f1" prefix-matches the token "f10" (normalized from "F-10");
        // the span covers only the matched characters.
        assertEquals(listOf("F-1"), spansAsText("form F-10 here", "f1"))
    }

    // @spec SEARCH-MATCH-002
    @Test
    fun folding_matchesCaseAndDiacritics() {
        assertEquals(listOf("Café"), spansAsText("the Café menu", "cafe"))
        assertEquals(listOf("CAFE"), spansAsText("the CAFE menu", "café"))
    }

    // @spec SEARCH-MATCH-003
    @Test
    fun lineWrapHyphenation_spanCoversBothLines() {
        val text = "spread of COVID-\n19 in 2026"
        assertEquals(listOf("COVID-\n19"), spansAsText(text, "covid19"))
    }

    // @spec SEARCH-MATCH-001
    @Test
    fun multiTokenQuery_unionInDocumentOrder() {
        val text = "the F-1 visa for a visa holder"
        val spans = NormalizedMatcher.findMatches(text, "visa f1")
        val texts = spans.map { text.substring(it) }
        assertEquals(listOf("F-1", "visa", "visa"), texts)
        // Document order: starts strictly increasing.
        assertTrue(spans.zipWithNext().all { (a, b) -> a.first < b.first })
    }

    // @spec SEARCH-MATCH-001
    @Test
    fun noOccurrences_returnsEmpty() {
        assertEquals(emptyList<String>(), spansAsText("nothing relevant here", "f1"))
    }
}
