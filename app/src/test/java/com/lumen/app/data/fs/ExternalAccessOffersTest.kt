package com.lumen.app.data.fs

import org.junit.Assert.assertEquals
import org.junit.Test

/** The external-access offer decision matrix (LIB-EXT-007..010): every offer
 *  cell across both API tiers, all storage locations, and all caller-supplied
 *  states. */
class ExternalAccessOffersTest {

    private val extAuthority = "com.android.externalstorage.documents"

    // Device-storage doc-form URIs (what external VIEW intents carry).
    private val nestedDoc =
        "content://$extAuthority/document/primary%3ADocs%2FReports%2Freport.pdf"
    private val downloadsRootDoc =
        "content://$extAuthority/document/primary%3ADownload%2Fstatement.pdf"
    private val downloadsSubfolderDoc =
        "content://$extAuthority/document/primary%3ADownload%2FInvoices%2Finv.pdf"
    private val volumeRootDoc =
        "content://$extAuthority/document/primary%3Aloose.pdf"
    private val androidDataDoc =
        "content://$extAuthority/document/primary%3AAndroid%2Fdata%2Fcom.app%2Ffiles%2Fx.pdf"
    private val sdNestedDoc =
        "content://$extAuthority/document/1A2B-3C4D%3AScans%2Fscan.pdf"
    private val sdVolumeRootDoc =
        "content://$extAuthority/document/1A2B-3C4D%3Ascan.pdf"
    private val sdDownloadsDoc =
        "content://$extAuthority/document/1A2B-3C4D%3ADownload%2Fx.pdf"

    // Non-device-storage providers.
    private val downloadsProviderDoc =
        "content://com.android.providers.downloads.documents/document/msf%3A1234"
    private val driveDoc =
        "content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3Dabc"

    private fun decide(
        docUri: String,
        apiLevel: Int = 34,
        persisted: Boolean = false,
        covered: Boolean = false,
        dismissed: Boolean = false,
    ) = ExternalAccessOffers.decide(docUri, apiLevel, persisted, covered, dismissed)

    // ── NONE: persisted / covered / dismissed win over everything ─────────

    // @spec LIB-EXT-007
    @Test
    fun none_whenAccessAlreadyPersists() {
        assertEquals(ExternalAccessOffer.NONE, decide(nestedDoc, persisted = true))
        assertEquals(ExternalAccessOffer.NONE, decide(downloadsRootDoc, persisted = true))
        assertEquals(ExternalAccessOffer.NONE, decide(driveDoc, persisted = true))
    }

    // @spec LIB-EXT-007
    @Test
    fun none_whenALibraryFolderCoversTheDocument() {
        assertEquals(ExternalAccessOffer.NONE, decide(nestedDoc, covered = true))
        assertEquals(ExternalAccessOffer.NONE, decide(downloadsRootDoc, covered = true))
    }

    // @spec LIB-EXT-007
    @Test
    fun none_whenTheOfferWasDismissed() {
        assertEquals(ExternalAccessOffer.NONE, decide(nestedDoc, dismissed = true))
        assertEquals(ExternalAccessOffer.NONE, decide(downloadsProviderDoc, dismissed = true))
        assertEquals(
            ExternalAccessOffer.NONE,
            decide(downloadsRootDoc, apiLevel = 29, dismissed = true),
        )
    }

    // ── ADD_FOLDER: grantable device-storage folders ──────────────────────

    // @spec LIB-EXT-008
    @Test
    fun addFolder_forNestedDeviceStorageDocuments_bothApiTiers() {
        assertEquals(ExternalAccessOffer.ADD_FOLDER, decide(nestedDoc, apiLevel = 29))
        assertEquals(ExternalAccessOffer.ADD_FOLDER, decide(nestedDoc, apiLevel = 30))
        assertEquals(ExternalAccessOffer.ADD_FOLDER, decide(nestedDoc, apiLevel = 34))
        assertEquals(ExternalAccessOffer.ADD_FOLDER, decide(sdNestedDoc, apiLevel = 34))
    }

    // A Download SUBFOLDER is grantable — only the root is restricted.
    // @spec LIB-EXT-008
    @Test
    fun addFolder_forDownloadSubfolderDocuments() {
        assertEquals(ExternalAccessOffer.ADD_FOLDER, decide(downloadsSubfolderDoc, apiLevel = 30))
        assertEquals(ExternalAccessOffer.ADD_FOLDER, decide(downloadsSubfolderDoc, apiLevel = 29))
    }

    // Pre-30 the picker grants anything: roots and restricted paths included.
    // @spec LIB-EXT-008, LIB-EXT-010
    @Test
    fun addFolder_onApi29_forEveryDeviceStorageLocation() {
        assertEquals(ExternalAccessOffer.ADD_FOLDER, decide(downloadsRootDoc, apiLevel = 29))
        assertEquals(ExternalAccessOffer.ADD_FOLDER, decide(volumeRootDoc, apiLevel = 29))
        assertEquals(ExternalAccessOffer.ADD_FOLDER, decide(androidDataDoc, apiLevel = 29))
        assertEquals(ExternalAccessOffer.ADD_FOLDER, decide(sdVolumeRootDoc, apiLevel = 29))
    }

    // ── KEEP_FILE: no folder grant possible ───────────────────────────────

    // @spec LIB-EXT-009
    @Test
    fun keepFile_forNonDeviceStorageProviders_bothApiTiers() {
        assertEquals(ExternalAccessOffer.KEEP_FILE, decide(downloadsProviderDoc, apiLevel = 29))
        assertEquals(ExternalAccessOffer.KEEP_FILE, decide(downloadsProviderDoc, apiLevel = 34))
        assertEquals(ExternalAccessOffer.KEEP_FILE, decide(driveDoc, apiLevel = 29))
        assertEquals(ExternalAccessOffer.KEEP_FILE, decide(driveDoc, apiLevel = 34))
    }

    // @spec LIB-EXT-009
    @Test
    fun keepFile_forVolumeRootDocumentsOnApi30Plus() {
        assertEquals(ExternalAccessOffer.KEEP_FILE, decide(volumeRootDoc, apiLevel = 30))
        assertEquals(ExternalAccessOffer.KEEP_FILE, decide(sdVolumeRootDoc, apiLevel = 34))
    }

    // @spec LIB-EXT-009
    @Test
    fun keepFile_forRestrictedAndroidPathsOnApi30Plus() {
        assertEquals(ExternalAccessOffer.KEEP_FILE, decide(androidDataDoc, apiLevel = 30))
        val obbDoc = "content://$extAuthority/document/primary%3AAndroid%2Fobb%2Fcom.app%2Fx.pdf"
        assertEquals(ExternalAccessOffer.KEEP_FILE, decide(obbDoc, apiLevel = 34))
    }

    // @spec LIB-EXT-009
    @Test
    fun keepFile_whenNoDocumentIdIsDerivable() {
        assertEquals(
            ExternalAccessOffer.KEEP_FILE,
            decide("content://$extAuthority/root/primary", apiLevel = 34),
        )
        // Volume-qualified id with an empty path names no file in a folder.
        assertEquals(
            ExternalAccessOffer.KEEP_FILE,
            decide("content://$extAuthority/document/primary%3A", apiLevel = 34),
        )
    }

    // ── LUMEN_PDFS_FLOW: Download root on API >= 30 ───────────────────────

    // @spec LIB-EXT-010
    @Test
    fun lumenPdfsFlow_forDownloadRootDocumentsOnApi30Plus() {
        assertEquals(ExternalAccessOffer.LUMEN_PDFS_FLOW, decide(downloadsRootDoc, apiLevel = 30))
        assertEquals(ExternalAccessOffer.LUMEN_PDFS_FLOW, decide(downloadsRootDoc, apiLevel = 34))
        // The restriction names "the Download directory" without a volume.
        assertEquals(ExternalAccessOffer.LUMEN_PDFS_FLOW, decide(sdDownloadsDoc, apiLevel = 34))
    }
}
