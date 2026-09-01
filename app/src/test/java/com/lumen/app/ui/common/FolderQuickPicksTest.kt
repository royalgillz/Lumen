package com.lumen.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Quick-pick chips launch the SAF folder picker with an EXTRA_INITIAL_URI for
 * a well-known folder on the external-storage provider. The URI string is
 * built framework-free (equivalent to DocumentsContract.buildDocumentUri) so
 * its shape is verifiable here.
 */
class FolderQuickPicksTest {

    // Android's on-disk folder is literally "Download", not "Downloads".
    // @spec LIB-QPK-002
    @Test
    fun downloads_targetsTheLiteralDownloadFolder() {
        assertEquals(
            "content://com.android.externalstorage.documents/document/primary%3ADownload",
            quickPickInitialUriString(QuickPickFolder.DOWNLOADS.documentId),
        )
    }

    // @spec LIB-QPK-002
    @Test
    fun documents_targetsTheDocumentsFolder() {
        assertEquals(
            "content://com.android.externalstorage.documents/document/primary%3ADocuments",
            quickPickInitialUriString(QuickPickFolder.DOCUMENTS.documentId),
        )
    }

    // @spec LIB-QPK-002
    @Test
    fun chipLabels_useThePluralUsersKnowFromTheFilesApp() {
        assertEquals("Downloads", QuickPickFolder.DOWNLOADS.label)
        assertEquals("Documents", QuickPickFolder.DOCUMENTS.label)
    }

    // The whole document id is one URI path segment: subpath separators are
    // encoded, never left as path structure.
    @Test
    fun encode_escapesSubpathSeparators() {
        assertEquals("primary%3ADocuments%2FInvoices", encodeDocumentId("primary:Documents/Invoices"))
    }

    // Matches android.net.Uri's segment encoding: unreserved characters stay
    // literal, everything else is percent-encoded per UTF-8 byte.
    @Test
    fun encode_leavesUnreservedCharactersAndEncodesUtf8Bytes() {
        assertEquals("primary%3AA-b_c.1", encodeDocumentId("primary:A-b_c.1"))
        assertEquals("primary%3AD%C3%A9j%C3%A0", encodeDocumentId("primary:Déjà"))
        assertEquals("primary%3Aa%20b", encodeDocumentId("primary:a b"))
    }
}
