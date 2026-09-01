package com.lumen.app.data.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The grantability predicates feeding the external-access offer matrix. */
class DocumentLocationsExternalTest {

    @Test
    fun authorityOf_extractsContentAuthorities() {
        assertEquals(
            "com.android.externalstorage.documents",
            DocumentLocations.authorityOf(
                "content://com.android.externalstorage.documents/document/primary%3Aa.pdf"
            ),
        )
        assertEquals(
            "com.android.providers.downloads.documents",
            DocumentLocations.authorityOf(
                "content://com.android.providers.downloads.documents/document/msf%3A12"
            ),
        )
    }

    @Test
    fun authorityOf_nullForNonContentUris() {
        assertNull(DocumentLocations.authorityOf("https://example.com/x.pdf"))
        assertNull(DocumentLocations.authorityOf("file:///sdcard/x.pdf"))
        assertNull(DocumentLocations.authorityOf("content://"))
    }

    // @spec LIB-EXT-008
    @Test
    fun containingFolderId_dropsTheLastSegment() {
        assertEquals(
            "primary:Docs/Reports",
            DocumentLocations.containingFolderIdOf("primary:Docs/Reports/report.pdf"),
        )
        assertEquals(
            "primary:Download",
            DocumentLocations.containingFolderIdOf("primary:Download/a.pdf"),
        )
    }

    // @spec LIB-EXT-008
    @Test
    fun containingFolderId_volumeRootForLooseFiles() {
        assertEquals("primary:", DocumentLocations.containingFolderIdOf("primary:loose.pdf"))
        assertEquals("1A2B-3C4D:", DocumentLocations.containingFolderIdOf("1A2B-3C4D:scan.pdf"))
    }

    // @spec LIB-EXT-008
    @Test
    fun containingFolderId_nullForUnqualifiedOrRootIds() {
        assertNull(DocumentLocations.containingFolderIdOf("no-volume-separator"))
        assertNull(DocumentLocations.containingFolderIdOf("primary:"))
    }

    // @spec LIB-EXT-010
    @Test
    fun isDownloadsRoot_matchesTheDownloadDirectoryOnAnyVolume() {
        assertTrue(DocumentLocations.isDownloadsRoot("primary:Download"))
        assertTrue(DocumentLocations.isDownloadsRoot("primary:download")) // FUSE is case-insensitive
        assertTrue(DocumentLocations.isDownloadsRoot("1A2B-3C4D:Download"))
        assertFalse(DocumentLocations.isDownloadsRoot("primary:Download/Invoices"))
        assertFalse(DocumentLocations.isDownloadsRoot("primary:Downloads")) // no such on-disk name
        assertFalse(DocumentLocations.isDownloadsRoot("primary:"))
    }

    // @spec LIB-EXT-008
    @Test
    fun isVolumeRoot_matchesBareVolumeIds() {
        assertTrue(DocumentLocations.isVolumeRoot("primary:"))
        assertTrue(DocumentLocations.isVolumeRoot("1A2B-3C4D:"))
        assertFalse(DocumentLocations.isVolumeRoot("primary:Docs"))
        assertFalse(DocumentLocations.isVolumeRoot("no-separator"))
    }

    // @spec LIB-EXT-008
    @Test
    fun isRestrictedPath_matchesAndroidDataAndObbSubtrees() {
        assertTrue(DocumentLocations.isRestrictedPath("primary:Android/data"))
        assertTrue(DocumentLocations.isRestrictedPath("primary:Android/data/com.app/files"))
        assertTrue(DocumentLocations.isRestrictedPath("primary:Android/obb/com.app"))
        assertTrue(DocumentLocations.isRestrictedPath("primary:android/DATA/x")) // case-insensitive
        assertFalse(DocumentLocations.isRestrictedPath("primary:Android")) // Android/ itself is fine
        assertFalse(DocumentLocations.isRestrictedPath("primary:Android/media/com.app"))
        assertFalse(DocumentLocations.isRestrictedPath("primary:Android/database")) // boundary, not prefix
        assertFalse(DocumentLocations.isRestrictedPath("primary:Docs/Android/data")) // not at the root
    }

    // @spec LIB-EXT-008
    @Test
    fun containingFolderGrantable_api30RefusesRootsDownloadAndRestricted() {
        val auth = "content://com.android.externalstorage.documents/document/"
        assertTrue(
            DocumentLocations.containingFolderGrantable("${auth}primary%3ADocs%2Fa.pdf", 30)
        )
        assertFalse(
            DocumentLocations.containingFolderGrantable("${auth}primary%3Aa.pdf", 30)
        )
        assertFalse(
            DocumentLocations.containingFolderGrantable("${auth}primary%3ADownload%2Fa.pdf", 30)
        )
        assertFalse(
            DocumentLocations.containingFolderGrantable(
                "${auth}primary%3AAndroid%2Fdata%2Fcom.app%2Fa.pdf", 30
            )
        )
    }

    // @spec LIB-EXT-008
    @Test
    fun containingFolderGrantable_api29GrantsEverythingDerivable() {
        val auth = "content://com.android.externalstorage.documents/document/"
        assertTrue(DocumentLocations.containingFolderGrantable("${auth}primary%3Aa.pdf", 29))
        assertTrue(
            DocumentLocations.containingFolderGrantable("${auth}primary%3ADownload%2Fa.pdf", 29)
        )
        // Still false when no folder is derivable at all.
        assertFalse(DocumentLocations.containingFolderGrantable("${auth}primary%3A", 29))
        assertFalse(
            DocumentLocations.containingFolderGrantable("content://a/root/primary", 29)
        )
    }
}
