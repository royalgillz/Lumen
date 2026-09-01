package com.lumen.app.data.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** EXTRA_INITIAL_URI construction for the offers' pickers — pure string work
 *  matching what DocumentsContract.buildDocumentUri produces. */
class ExternalAccessPickerTargetsTest {

    private val extAuthority = "com.android.externalstorage.documents"

    // @spec LIB-EXT-013
    @Test
    fun containingFolder_aimsAtTheDocumentsOwnFolder() {
        assertEquals(
            "content://$extAuthority/document/primary%3ADocs%2FReports",
            ExternalAccessPickerTargets.containingFolderInitialUri(
                "content://$extAuthority/document/primary%3ADocs%2FReports%2Freport.pdf"
            ),
        )
        assertEquals(
            "content://$extAuthority/document/primary%3ADownload%2FInvoices",
            ExternalAccessPickerTargets.containingFolderInitialUri(
                "content://$extAuthority/document/primary%3ADownload%2FInvoices%2Finv.pdf"
            ),
        )
    }

    // @spec LIB-EXT-013
    @Test
    fun containingFolder_volumeRootForLooseFiles() {
        assertEquals(
            "content://$extAuthority/document/primary%3A",
            ExternalAccessPickerTargets.containingFolderInitialUri(
                "content://$extAuthority/document/primary%3Aloose.pdf"
            ),
        )
    }

    // @spec LIB-EXT-013
    @Test
    fun containingFolder_reEncodesNonAsciiAndReservedCharacters() {
        assertEquals(
            "content://$extAuthority/document/primary%3AR%C3%A9sum%C3%A9s%20%2B%20CVs",
            ExternalAccessPickerTargets.containingFolderInitialUri(
                "content://$extAuthority/document/primary%3AR%C3%A9sum%C3%A9s%20%2B%20CVs%2Fcv.pdf"
            ),
        )
    }

    // @spec LIB-EXT-013
    @Test
    fun containingFolder_nullForOtherProvidersAndUnderivableUris() {
        assertNull(
            ExternalAccessPickerTargets.containingFolderInitialUri(
                "content://com.android.providers.downloads.documents/document/msf%3A12"
            )
        )
        assertNull(
            ExternalAccessPickerTargets.containingFolderInitialUri(
                "content://$extAuthority/document/primary%3A"
            )
        )
        assertNull(
            ExternalAccessPickerTargets.containingFolderInitialUri(
                "content://$extAuthority/root/primary"
            )
        )
    }

    // @spec LIB-EXT-013
    @Test
    fun downloads_isTheOnDiskDownloadFolder() {
        assertEquals(
            "content://$extAuthority/document/primary%3ADownload",
            ExternalAccessPickerTargets.downloadsInitialUri(),
        )
    }

    // @spec LIB-EXT-013
    @Test
    fun lumenPdfs_isTheGuidedFlowFolder() {
        assertEquals(
            "content://$extAuthority/document/primary%3ADownload%2FLumen-pdfs",
            ExternalAccessPickerTargets.lumenPdfsInitialUri(),
        )
    }

    // The Lumen-pdfs constants agree with each other: the MediaStore relative
    // path and the SAF document id name the same on-disk folder.
    // @spec LIB-EXT-011
    @Test
    fun lumenPdfsFolder_constantsAgree() {
        assertEquals("Download/Lumen-pdfs", LumenPdfsFolder.MEDIA_STORE_RELATIVE_PATH)
        assertEquals("primary:Download/Lumen-pdfs", LumenPdfsFolder.DOCUMENT_ID)
        assertEquals(
            LumenPdfsFolder.DOCUMENT_ID,
            "primary:" + LumenPdfsFolder.MEDIA_STORE_RELATIVE_PATH,
        )
        assertEquals("Move PDFs here and Lumen will index them.", LumenPdfsFolder.README_CONTENT)
        assertEquals("Read me.txt", LumenPdfsFolder.README_NAME)
    }
}
