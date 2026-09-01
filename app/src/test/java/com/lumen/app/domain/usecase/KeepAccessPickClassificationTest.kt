package com.lumen.app.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Keep-access pick classification: same string → keep in place; same provider
 * document under a different URI form or encoding → re-key; anything else —
 * different id, different provider, underivable identity — is a different
 * document and records fresh.
 */
class KeepAccessPickClassificationTest {

    private val ext = "com.android.externalstorage.documents"

    // @spec LIB-EXT-017
    @Test
    fun identicalUri_sameUri() {
        val uri = "content://$ext/document/primary%3ADownload%2Freport.pdf"
        assertEquals(KeepAccessPick.SAME_URI, classifyKeepAccessPick(uri, uri))
    }

    // @spec LIB-EXT-017
    @Test
    fun docFormVsTreeForm_sameDocument() {
        // A VIEW intent hands the doc form; the picker returns a tree-form
        // child URI for the same provider document.
        val original = "content://$ext/document/primary%3ADocs%2Freport.pdf"
        val picked = "content://$ext/tree/primary%3ADocs/document/primary%3ADocs%2Freport.pdf"
        assertEquals(KeepAccessPick.SAME_DOCUMENT, classifyKeepAccessPick(original, picked))
    }

    // @spec LIB-EXT-017
    @Test
    fun encodingVariantsOfSameId_sameDocument() {
        // Providers are free to vary percent-encoding between forms; identity
        // compares decoded ids.
        val original = "content://$ext/document/primary%3AR%C3%A9sum%C3%A9s%2Fcv%20final.pdf"
        val picked = "content://$ext/document/primary%3AR%c3%a9sum%c3%a9s%2Fcv%20final.pdf"
        assertEquals(KeepAccessPick.SAME_DOCUMENT, classifyKeepAccessPick(original, picked))
    }

    // @spec LIB-EXT-017
    @Test
    fun plusStaysLiteralInIds() {
        // '+' in a document id is a literal plus, not an encoded space — the
        // two must not conflate.
        val plus = "content://$ext/document/primary%3ADocs%2Fa+b.pdf"
        val space = "content://$ext/document/primary%3ADocs%2Fa%20b.pdf"
        assertEquals(KeepAccessPick.DIFFERENT_DOCUMENT, classifyKeepAccessPick(plus, space))
    }

    // @spec LIB-EXT-017
    @Test
    fun differentId_differentDocument() {
        val original = "content://$ext/document/primary%3ADownload%2Freport.pdf"
        val picked = "content://$ext/document/primary%3ADownload%2Fother.pdf"
        assertEquals(KeepAccessPick.DIFFERENT_DOCUMENT, classifyKeepAccessPick(original, picked))
    }

    // @spec LIB-EXT-017
    @Test
    fun sameIdDifferentAuthority_differentDocument() {
        val original = "content://com.google.android.apps.docs.storage/document/primary%3Aa.pdf"
        val picked = "content://$ext/document/primary%3Aa.pdf"
        assertEquals(KeepAccessPick.DIFFERENT_DOCUMENT, classifyKeepAccessPick(original, picked))
    }

    // @spec LIB-EXT-017
    @Test
    fun downloadsProviderMsfIds_matchExactly() {
        val original = "content://com.android.providers.downloads.documents/document/msf%3A1234"
        assertEquals(
            KeepAccessPick.SAME_DOCUMENT,
            classifyKeepAccessPick(original, "content://com.android.providers.downloads.documents/document/msf:1234"),
        )
        assertEquals(
            KeepAccessPick.DIFFERENT_DOCUMENT,
            classifyKeepAccessPick(original, "content://com.android.providers.downloads.documents/document/msf%3A1235"),
        )
    }

    // @spec LIB-EXT-017
    @Test
    fun underivableOriginal_differentDocument() {
        // No decodable identity on the original: never guess a re-key.
        assertEquals(
            KeepAccessPick.DIFFERENT_DOCUMENT,
            classifyKeepAccessPick("file:///sdcard/a.pdf", "content://$ext/document/primary%3Aa.pdf"),
        )
        assertEquals(
            KeepAccessPick.DIFFERENT_DOCUMENT,
            classifyKeepAccessPick("content://$ext/tree/primary%3ADocs", "content://$ext/document/primary%3ADocs"),
        )
    }

    // @spec LIB-EXT-017
    @Test
    fun underivablePicked_differentDocument() {
        val original = "content://$ext/document/primary%3Aa.pdf"
        assertEquals(
            KeepAccessPick.DIFFERENT_DOCUMENT,
            classifyKeepAccessPick(original, "content://$ext/root/primary"),
        )
    }

    // @spec LIB-EXT-017
    @Test
    fun identicalNonContentStrings_stillSameUri() {
        // String equality wins before any identity derivation — an exact match
        // can only be the same row.
        assertEquals(
            KeepAccessPick.SAME_URI,
            classifyKeepAccessPick("file:///sdcard/a.pdf", "file:///sdcard/a.pdf"),
        )
    }
}
