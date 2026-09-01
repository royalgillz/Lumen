package com.lumen.app.data.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // @spec SEARCH-MATCH-005
    @Test
    fun quotedPhrase_adjacentWords_oneSpanPerOccurrence() {
        assertEquals(
            listOf("invoice 4471"),
            spansAsText("pay invoice 4471 now", "\"invoice 4471\""),
        )
        // Two occurrences — two spans, not four word spans.
        assertEquals(
            listOf("invoice 4471", "invoice 4471"),
            spansAsText("invoice 4471 then invoice 4471", "\"invoice 4471\""),
        )
    }

    // @spec SEARCH-MATCH-005
    @Test
    fun quotedPhrase_nonAdjacentWords_noMatch() {
        assertEquals(emptyList<String>(), spansAsText("invoice for 4471", "\"invoice 4471\""))
        assertEquals(emptyList<String>(), spansAsText("4471 invoice", "\"invoice 4471\""))
    }

    // @spec SEARCH-MATCH-005
    @Test
    fun quotedPhrase_toleratesDeletedPunctuationAndWhitespaceBetweenWords() {
        assertEquals(
            listOf("invoice, 4471"),
            spansAsText("see invoice, 4471 here", "\"invoice 4471\""),
        )
        assertEquals(
            listOf("invoice\n4471"),
            spansAsText("total invoice\n4471 due", "\"invoice 4471\""),
        )
    }

    // @spec SEARCH-MATCH-005
    @Test
    fun quotedPhrase_doesNotMatchMergedToken() {
        // "invoice-4471" normalizes to ONE indexed token; the FTS phrase does
        // not match it, so the matcher must not either.
        assertEquals(emptyList<String>(), spansAsText("invoice-4471 paid", "\"invoice 4471\""))
    }

    // @spec SEARCH-MATCH-005
    @Test
    fun quotedPhrase_tokensKeepPrefixSemantics() {
        // Mirrors the FTS phrase "invoice* 44*".
        assertEquals(listOf("invoice 44"), spansAsText("pay invoice 4471 now", "\"invoice 44\""))
        // But a phrase token never matches mid-word: "voice 4471" is not there.
        assertEquals(emptyList<String>(), spansAsText("pay invoice 4471 now", "\"voice 4471\""))
    }

    // @spec SEARCH-MATCH-002, SEARCH-MATCH-005
    @Test
    fun quotedPhrase_foldsCaseAndDiacritics() {
        assertEquals(listOf("Café Noir"), spansAsText("the Café Noir menu", "\"cafe noir\""))
    }

    // @spec SEARCH-MATCH-005
    @Test
    fun singleWordInQuotes_behavesAsPlainToken() {
        assertEquals(
            spansAsText("a visa for a visa holder", "visa"),
            spansAsText("a visa for a visa holder", "\"visa\""),
        )
    }

    // @spec SEARCH-MATCH-005
    @Test
    fun mixedQuery_phraseAndTokenSpansInDocumentOrder() {
        val text = "paid invoice 4471 today"
        val spans = NormalizedMatcher.findMatches(text, "paid \"invoice 4471\"")
        assertEquals(listOf("paid", "invoice 4471"), spans.map { text.substring(it) })
    }

    // @spec SEARCH-NOTE-001
    @Test
    fun matchesAllPhrases_requiresEveryPhrase() {
        assertTrue(NormalizedMatcher.matchesAllPhrases("invoice 4471 paid", "paid \"invoice 4471\""))
        assertFalse(NormalizedMatcher.matchesAllPhrases("invoice 4471", "paid \"invoice 4471\""))
        assertFalse(NormalizedMatcher.matchesAllPhrases("4471 invoice paid", "\"invoice 4471\""))
        assertFalse(NormalizedMatcher.matchesAllPhrases("", "invoice"))
    }

    // @spec SEARCH-QRY-011
    @Test
    fun containsAdjacent_filenameLaneSemantics() {
        assertTrue(NormalizedMatcher.containsAdjacent("invoice 4471 final", listOf("invoice", "4471")))
        assertFalse(NormalizedMatcher.containsAdjacent("invoice final 4471", listOf("invoice", "4471")))
        // First token may start mid-word (the lane's contains looseness)...
        assertTrue(NormalizedMatcher.containsAdjacent("myinvoice 4471", listOf("invoice", "4471")))
        // ...later tokens must start the immediately following word.
        assertFalse(NormalizedMatcher.containsAdjacent("invoice x4471", listOf("invoice", "4471")))
        assertTrue(NormalizedMatcher.containsAdjacent("invoices 4471", listOf("invoice", "4471")))
    }
}
