package com.lumen.app.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FtsQuerySanitizerTest {

    // @spec SEARCH-QRY-001
    @Test
    fun multiWord_spaceJoinedWithPrefixWildcard() {
        // Space = implicit AND in both standard and enhanced FTS4 query syntax.
        // " AND " would be a literal term under the standard syntax.
        assertEquals("hello* world*", FtsQuerySanitizer.sanitize("hello world"))
    }

    // @spec SEARCH-QRY-001
    @Test
    fun punctuatedIdentifier_normalizedToSingleToken() {
        assertEquals("f1*", FtsQuerySanitizer.sanitize("f-1"))
        assertEquals("F1*", FtsQuerySanitizer.sanitize("F.1"))
        assertEquals("I20*", FtsQuerySanitizer.sanitize("I-20"))
        assertEquals("80211*", FtsQuerySanitizer.sanitize("802.11"))
    }

    // @spec SEARCH-QRY-001
    @Test
    fun compactForm_unchangedByNormalization() {
        assertEquals("F1*", FtsQuerySanitizer.sanitize("F1"))
    }

    // @spec SEARCH-QRY-001
    @Test
    fun slash_actsAsSeparatorNotDeletion() {
        // '/' is outside set P: stripped to a separator, not deleted.
        assertEquals("80211b* g*", FtsQuerySanitizer.sanitize("802.11b/g"))
    }

    // @spec SEARCH-QRY-001
    @Test
    fun ftsMetacharacters_stripped() {
        assertEquals("hello* world*", FtsQuerySanitizer.sanitize("hello \"world\""))
        assertEquals("hello* world*", FtsQuerySanitizer.sanitize("hello* world*"))
        assertEquals("hello* world*", FtsQuerySanitizer.sanitize("\"hello*\" (world)^"))
    }

    // @spec SEARCH-QRY-002
    @Test
    fun textualOperators_neverEmittedBare() {
        // "AND" typed by the user becomes the prefix term "AND*", never a bare operator.
        val out = FtsQuerySanitizer.sanitize("cats AND dogs")
        assertEquals("cats* AND* dogs*", out)
    }

    // @spec SEARCH-QRY-003
    @Test
    fun punctuationOnly_normalizesToNothing_returnsNull() {
        assertNull(FtsQuerySanitizer.sanitize("-.-"))
        assertNull(FtsQuerySanitizer.sanitize("  "))
        assertNull(FtsQuerySanitizer.sanitize(""))
    }

    // @spec SEARCH-QRY-005
    @Test
    fun minLengthGate_measuredOnNormalizedTokens() {
        // "f-" normalizes to the single char "f" — below the 2-char gate.
        assertNull(FtsQuerySanitizer.sanitize("f-"))
        assertNull(FtsQuerySanitizer.sanitize("a b c"))
        // One qualifying token opens the gate; short tokens are kept alongside it.
        assertEquals("a* bc*", FtsQuerySanitizer.sanitize("a bc"))
        assertEquals("f1*", FtsQuerySanitizer.sanitize("f1"))
    }

    // @spec SEARCH-QRY-001
    @Test
    fun leadingTrailingSpaces_trimmed() {
        assertEquals("hello*", FtsQuerySanitizer.sanitize("  hello  "))
    }

    // @spec SEARCH-QRY-007
    @Test
    fun repeatedToken_emittedOnce() {
        // Duplicate phrases double matchinfo hit totals; order of first
        // appearance is preserved.
        assertEquals("visa* form*", FtsQuerySanitizer.sanitize("visa form visa"))
        // unicode61 case-folds, so a case-variant repeat is the same phrase.
        assertEquals("Visa*", FtsQuerySanitizer.sanitize("Visa visa"))
        // Normalization collapses punctuation variants onto one token too.
        assertEquals("f1*", FtsQuerySanitizer.sanitize("f-1 f1"))
        // unicode61 also strips diacritics: "café" and "cafe" are ONE index
        // term — emitting both would double its matchinfo counts.
        assertEquals("café*", FtsQuerySanitizer.sanitize("café cafe"))
        assertEquals("Ré*", FtsQuerySanitizer.sanitize("Ré re"))
    }

    // @spec SEARCH-QRY-008
    @Test
    fun quotedPhrase_emittedAsFts4PhraseOfPrefixTerms() {
        assertEquals("\"invoice* 4471*\"", FtsQuerySanitizer.sanitize("\"invoice 4471\""))
    }

    // @spec SEARCH-QRY-008
    @Test
    fun quotedAndUnquoted_mixFreely_orderPreserved() {
        assertEquals(
            "\"invoice* 4471*\" paid*",
            FtsQuerySanitizer.sanitize("\"invoice 4471\" paid"),
        )
        assertEquals(
            "paid* \"invoice* 4471*\"",
            FtsQuerySanitizer.sanitize("paid \"invoice 4471\""),
        )
    }

    // @spec SEARCH-QRY-008
    @Test
    fun quotedPhrase_innerTokensSanitizedAndNormalized() {
        // Set-P punctuation deletes inside the phrase exactly as outside it;
        // residual FTS metacharacters are stripped, not emitted.
        assertEquals("\"invoice* 4471*\"", FtsQuerySanitizer.sanitize("\"in-voice 4,471\""))
        assertEquals("\"80211* ok*\"", FtsQuerySanitizer.sanitize("\"802.11 (ok)\""))
        // Diacritics pass through untouched — unicode61 folds them at match time.
        assertEquals("\"café* noir*\"", FtsQuerySanitizer.sanitize("\"café noir\""))
    }

    // @spec SEARCH-QRY-009
    @Test
    fun unbalancedQuote_strippedDegradesToImplicitAnd() {
        assertEquals("invoice* 4471*", FtsQuerySanitizer.sanitize("\"invoice 4471"))
        assertEquals("invoice* 4471*", FtsQuerySanitizer.sanitize("invoice\" 4471"))
        // A balanced pair followed by a dangling quote: the pair survives.
        assertEquals("\"invoice* 4471*\" paid*", FtsQuerySanitizer.sanitize("\"invoice 4471\" \"paid"))
    }

    // @spec SEARCH-QRY-009
    @Test
    fun singleWordInQuotes_identicalToPlainToken() {
        assertEquals("visa*", FtsQuerySanitizer.sanitize("\"visa\""))
        assertEquals(FtsQuerySanitizer.sanitize("visa"), FtsQuerySanitizer.sanitize("\"visa\""))
    }

    // @spec SEARCH-QRY-010
    @Test
    fun repeatedPhrase_emittedOnce_caseInsensitively() {
        assertEquals(
            "\"invoice* 4471*\"",
            FtsQuerySanitizer.sanitize("\"invoice 4471\" \"Invoice 4471\""),
        )
        // A quoted single token dedupes against the same plain token.
        assertEquals("visa*", FtsQuerySanitizer.sanitize("\"visa\" visa"))
        // The token phrase and the multi-word phrase are distinct query phrases.
        assertEquals(
            "invoice* \"invoice* 4471*\"",
            FtsQuerySanitizer.sanitize("invoice \"invoice 4471\""),
        )
    }

    // @spec SEARCH-QRY-005, SEARCH-QRY-008
    @Test
    fun minLengthGate_appliesAcrossPhraseTokens() {
        // No token anywhere reaches 2 chars — no search.
        assertNull(FtsQuerySanitizer.sanitize("\"a b\""))
        // One qualifying token inside the phrase opens the gate.
        assertEquals("\"ab* c*\"", FtsQuerySanitizer.sanitize("\"ab c\""))
        // Empty quotes contribute nothing.
        assertNull(FtsQuerySanitizer.sanitize("\"\""))
    }

    // @spec SEARCH-QRY-008
    @Test
    fun quotedPhrase_duplicateWordInsidePhrase_preserved() {
        // "the the" as a phrase genuinely means the word twice in a row — the
        // per-phrase dedupe must not collapse tokens WITHIN a phrase.
        assertEquals("\"the* the*\"", FtsQuerySanitizer.sanitize("\"the the\""))
    }

    // @spec SEARCH-QRY-006
    @Test
    fun tokenize_matchesSanitizePhrases_forPunctuatedQueries() {
        // Filename matching derives its tokens from the same tokenize path
        // (lowercased + deduped), so punctuated queries produce identical
        // token sets in both lanes.
        for (query in listOf("802.11b/g", "f-1 \"visa\"", "(hello) world^", "I-20 form")) {
            val contentTokens = FtsQuerySanitizer.sanitize(query)!!
                .split(" ")
                .map { it.removeSuffix("*").lowercase() }
            val filenameTokens = FtsQuerySanitizer.tokenize(query).map { it.lowercase() }.distinct()
            assertEquals(query, contentTokens, filenameTokens)
        }
    }
}
