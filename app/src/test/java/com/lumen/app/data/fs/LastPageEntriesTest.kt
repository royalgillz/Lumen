package com.lumen.app.data.fs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Bulk decode of the reading-position store: "key:page" entries (keys are
 * encoded URIs, or legacy uriHashCode strings) become one raw-keyed map so
 * list rows never read DataStore individually.
 */
class LastPageEntriesTest {

    // @spec LIB-PRG-002
    @Test
    fun parse_decodesEncodedAndLegacyEntries() {
        val entries = setOf("encDoc:17", "12345:9")

        assertEquals(
            mapOf("encDoc" to 17, "12345" to 9),
            parseLastPageEntries(entries),
        )
    }

    // Encoded keys have no raw ':' (Uri.encode escapes it), so the first ':'
    // always ends the key.
    // @spec LIB-PRG-002
    @Test
    fun parse_splitsOnTheFirstColon() {
        assertEquals(
            mapOf("content%3A%2F%2Fdoc" to 4),
            parseLastPageEntries(setOf("content%3A%2F%2Fdoc:4")),
        )
    }

    // @spec LIB-PRG-002
    @Test
    fun parse_skipsMalformedEntriesWithoutThrowing() {
        val entries = setOf(
            "noColonAtAll",      // no separator
            ":7",                // empty key
            "encDoc:notANumber", // non-numeric page
            "encDoc:",           // missing page
            "encOk:3",
        )

        assertEquals(mapOf("encOk" to 3), parseLastPageEntries(entries))
    }

    // @spec LIB-PRG-002
    @Test
    fun parse_emptyStoreYieldsEmptyMap() {
        assertEquals(emptyMap<String, Int>(), parseLastPageEntries(emptySet()))
    }
}
