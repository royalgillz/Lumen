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
}
