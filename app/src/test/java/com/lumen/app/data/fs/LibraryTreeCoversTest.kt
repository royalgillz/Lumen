package com.lumen.app.data.fs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Boundary-anchored library-folder coverage of external document URIs —
 *  the pure half of SafRepository.anyLibraryTreeCovers. */
class LibraryTreeCoversTest {

    private val extAuthority = "com.android.externalstorage.documents"
    private val docsTree = "content://$extAuthority/tree/primary%3ADocs"
    private val volumeRootTree = "content://$extAuthority/tree/primary%3A"

    // @spec LIB-EXT-012
    @Test
    fun covers_treeFormChildUrisAtTheDocumentBoundary() {
        assertTrue(
            libraryTreeCoversDoc(
                docsTree,
                "$docsTree/document/primary%3ADocs%2FReports%2Fr.pdf",
            )
        )
    }

    // @spec LIB-EXT-012
    @Test
    fun covers_docFormUrisWhoseIdExtendsTheTreeIdAtASlashBoundary() {
        assertTrue(
            libraryTreeCoversDoc(
                docsTree,
                "content://$extAuthority/document/primary%3ADocs%2Fr.pdf",
            )
        )
        assertTrue(
            libraryTreeCoversDoc(
                docsTree,
                "content://$extAuthority/document/primary%3ADocs%2FReports%2Fdeep%2Fr.pdf",
            )
        )
    }

    // The deleteByTreeUri rationale: `Docs` must never cover `Docs2`.
    // @spec LIB-EXT-012
    @Test
    fun neverCovers_siblingTreesByBarePrefix() {
        assertFalse(
            libraryTreeCoversDoc(
                docsTree,
                "content://$extAuthority/document/primary%3ADocs2%2Fr.pdf",
            )
        )
        assertFalse(
            libraryTreeCoversDoc(
                docsTree,
                "$docsTree" + "2/document/primary%3ADocs2%2Fr.pdf",
            )
        )
    }

    // @spec LIB-EXT-012
    @Test
    fun volumeRootTrees_boundAtTheColon() {
        assertTrue(
            libraryTreeCoversDoc(
                volumeRootTree,
                "content://$extAuthority/document/primary%3Aloose.pdf",
            )
        )
        assertFalse(
            libraryTreeCoversDoc(
                volumeRootTree,
                "content://$extAuthority/document/1A2B-3C4D%3Ascan.pdf",
            )
        )
        // The volume root id itself (no file) is not covered.
        assertFalse(
            libraryTreeCoversDoc(
                volumeRootTree,
                "content://$extAuthority/document/primary%3A",
            )
        )
    }

    // @spec LIB-EXT-012
    @Test
    fun neverCovers_acrossAuthorities() {
        assertFalse(
            libraryTreeCoversDoc(
                docsTree,
                "content://com.other.provider/document/primary%3ADocs%2Fr.pdf",
            )
        )
    }

    // @spec LIB-EXT-012
    @Test
    fun neverCovers_nonContentOrUnderivableUris() {
        assertFalse(libraryTreeCoversDoc(docsTree, "https://example.com/Docs/r.pdf"))
        assertFalse(libraryTreeCoversDoc(docsTree, "content://$extAuthority/root/primary"))
        assertFalse(libraryTreeCoversDoc("not a tree uri", "content://$extAuthority/document/x"))
    }

    // A tree-form URI under a DIFFERENT (deeper) tree still matches by id.
    // @spec LIB-EXT-012
    @Test
    fun covers_treeFormUrisFromDeeperTreesById() {
        assertTrue(
            libraryTreeCoversDoc(
                docsTree,
                "content://$extAuthority/tree/primary%3ADocs%2FReports/document/" +
                    "primary%3ADocs%2FReports%2Fr.pdf",
            )
        )
    }

    // @spec LIB-EXT-012
    @Test
    fun anyLibraryTreeCovers_isAnyOverTheFolderSet() {
        val doc = "content://$extAuthority/document/primary%3ADocs%2Fr.pdf"
        assertTrue(anyLibraryTreeCovers(listOf(volumeRootTree, docsTree), doc))
        assertFalse(anyLibraryTreeCovers(emptyList(), doc))
        assertFalse(
            anyLibraryTreeCovers(
                listOf("content://$extAuthority/tree/primary%3AOther"),
                doc,
            )
        )
    }
}
