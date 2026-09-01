package com.lumen.app.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafDocumentUrisTest {

    // @spec VIEW-EXT-002
    @Test
    fun authority_parsedFromContentUrisOnly() {
        assertEquals(
            "com.android.externalstorage.documents",
            SafDocumentUris.authorityOf(
                "content://com.android.externalstorage.documents/document/primary%3ADocs%2Fa.pdf"
            ),
        )
        assertNull(SafDocumentUris.authorityOf("file:///sdcard/a.pdf"))
        assertNull(SafDocumentUris.authorityOf("https://example.com/a.pdf"))
        assertNull(SafDocumentUris.authorityOf("content://"))
    }

    // The same file: tree form (library row) vs tree-less form (VIEW intent).
    // @spec VIEW-EXT-002
    @Test
    fun documentId_matchesAcrossTreeAndDocumentForms() {
        val treeForm = "content://com.android.externalstorage.documents/tree/" +
            "primary%3ADocs/document/primary%3ADocs%2Freport.pdf"
        val docForm = "content://com.android.externalstorage.documents/document/" +
            "primary%3ADocs%2Freport.pdf"
        assertEquals("primary:Docs/report.pdf", SafDocumentUris.documentIdOf(treeForm))
        assertEquals("primary:Docs/report.pdf", SafDocumentUris.documentIdOf(docForm))
        assertTrue(SafDocumentUris.sameDocument(treeForm, docForm))
    }

    // Providers are free to vary percent-encoding between forms of the same id.
    // @spec VIEW-EXT-002
    @Test
    fun documentId_decodedBeforeComparison() {
        val upper = "content://auth/document/primary%3AA%2Fb.pdf"
        val lower = "content://auth/document/primary%3aA%2fb.pdf"
        assertTrue(SafDocumentUris.sameDocument(upper, lower))
        assertEquals("primary:A/b.pdf", SafDocumentUris.documentIdOf(upper))
    }

    // @spec VIEW-EXT-002
    @Test
    fun documentId_handlesUtf8Escapes() {
        assertEquals(
            "primary:Ré – ü.pdf",
            SafDocumentUris.documentIdOf(
                "content://auth/document/primary%3AR%C3%A9%20%E2%80%93%20%C3%BC.pdf"
            ),
        )
    }

    // @spec VIEW-EXT-002
    @Test
    fun documentId_nullForNonDocumentShapes() {
        assertNull(SafDocumentUris.documentIdOf("content://auth/tree/primary%3ADocs"))
        assertNull(SafDocumentUris.documentIdOf("content://auth"))
        assertNull(SafDocumentUris.documentIdOf("content://auth/document"))
        assertNull(SafDocumentUris.documentIdOf("content://auth/other/primary%3Aa.pdf"))
        assertNull(SafDocumentUris.documentIdOf("file:///document/a.pdf"))
    }

    // Different authorities or different ids must never match — a Drive file
    // and a local file with the same display name are different documents.
    // @spec VIEW-EXT-002
    @Test
    fun sameDocument_requiresSameAuthorityAndId() {
        val a = "content://authA/document/primary%3Aa.pdf"
        val b = "content://authB/document/primary%3Aa.pdf"
        val c = "content://authA/document/primary%3Ac.pdf"
        assertFalse(SafDocumentUris.sameDocument(a, b))
        assertFalse(SafDocumentUris.sameDocument(a, c))
        assertTrue(SafDocumentUris.sameDocument(a, a))
    }

    // Malformed escapes pass through as-is (Uri.decode behavior), never throw.
    @Test
    fun malformedEscapes_passThrough() {
        assertEquals("a%ZZb", SafDocumentUris.documentIdOf("content://auth/document/a%ZZb"))
        assertEquals("trail%2", SafDocumentUris.documentIdOf("content://auth/document/trail%2"))
    }
}
