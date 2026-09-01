package com.lumen.app.data.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentLocationsTest {

    private val nested =
        "content://com.android.externalstorage.documents/tree/primary%3ADocs/document/primary%3ADocs%2FReports%2Freport.pdf"
    private val atPickedRoot =
        "content://com.android.externalstorage.documents/tree/primary%3ADocs/document/primary%3ADocs%2Freport.pdf"
    private val atVolumeRoot =
        "content://com.android.externalstorage.documents/tree/primary%3A/document/primary%3Areport.pdf"
    private val onSdCard =
        "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3A/document/1A2B-3C4D%3Ascan.pdf"

    @Test
    fun documentId_decodesTreeFormUris() {
        assertEquals("primary:Docs/Reports/report.pdf", DocumentLocations.documentIdOf(nested))
        assertEquals("primary:report.pdf", DocumentLocations.documentIdOf(atVolumeRoot))
    }

    @Test
    fun documentId_keepsPlusAndUtf8Characters() {
        val uri = "content://a/tree/primary%3AD/document/primary%3AD%2Fr%C3%A9sum%C3%A9+1.pdf"
        assertEquals("primary:D/résumé+1.pdf", DocumentLocations.documentIdOf(uri))
    }

    @Test
    fun documentId_nullForNonDocumentUris() {
        assertNull(DocumentLocations.documentIdOf("content://a/tree/primary%3ADocs"))
        assertNull(DocumentLocations.documentIdOf("https://example.com/x.pdf"))
    }

    // @spec LIB-LOC-001
    @Test
    fun folderDisplayPath_showsTheContainingFolders() {
        assertEquals("Docs/Reports", DocumentLocations.folderDisplayPath(nested))
        assertEquals("Docs", DocumentLocations.folderDisplayPath(atPickedRoot))
    }

    // @spec LIB-LOC-001
    @Test
    fun folderDisplayPath_volumeLabelAtVolumeRoot() {
        assertEquals("Internal storage", DocumentLocations.folderDisplayPath(atVolumeRoot))
        assertEquals("1A2B-3C4D", DocumentLocations.folderDisplayPath(onSdCard))
    }

    // @spec LIB-LOC-002
    @Test
    fun parentDocumentId_isTheFolderId() {
        assertEquals("primary:Docs/Reports", DocumentLocations.parentDocumentId(nested))
        assertEquals("primary:Docs", DocumentLocations.parentDocumentId(atPickedRoot))
    }

    // A file directly under the picked volume root falls back to the tree id.
    // @spec LIB-LOC-002
    @Test
    fun parentDocumentId_fallsBackToTreeIdAtVolumeRoot() {
        assertEquals("primary:", DocumentLocations.parentDocumentId(atVolumeRoot))
        assertEquals("1A2B-3C4D:", DocumentLocations.parentDocumentId(onSdCard))
    }
}
