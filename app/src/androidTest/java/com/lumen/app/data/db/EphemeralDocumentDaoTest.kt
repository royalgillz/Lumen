package com.lumen.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumen.app.data.db.entity.BookmarkEntity
import com.lumen.app.data.db.entity.DocumentEntity
import com.lumen.app.data.db.entity.PageEntity
import com.lumen.app.data.db.entity.PageTextEntity
import com.lumen.app.data.text.TextNormalizer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The ephemeral visibility split: externally-opened docs indexed under a TTL
 * are search-visible but invisible to every library surface, and the purge
 * removes their index rows without touching URI-keyed user data (bookmarks).
 */
@RunWith(AndroidJUnit4::class)
class EphemeralDocumentDaoTest {

    private lateinit var db: LumenDatabase

    private val tree = "content://com.android.externalstorage.documents/tree/primary%3ADocs"

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, LumenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insertLibraryDoc(filename: String, lastOpenedAt: Long? = null): Long =
        db.documentDao().upsert(
            DocumentEntity(
                uri = "$tree/document/primary%3ADocs%2F$filename",
                filename = filename,
                treeUri = tree,
                status = "indexed",
                lastOpenedAt = lastOpenedAt,
            )
        )

    /** An externally-opened doc the TTL indexer wrote: no tree, non-null expiry. */
    private suspend fun insertEphemeralDoc(
        filename: String,
        expiresAt: Long,
        lastOpenedAt: Long? = null,
    ): Long =
        db.documentDao().upsert(
            DocumentEntity(
                uri = "content://ext/$filename",
                filename = filename,
                treeUri = "",
                status = "indexed",
                lastOpenedAt = lastOpenedAt,
                ephemeralExpiresAt = expiresAt,
            )
        )

    private suspend fun insertPageWithText(docId: Long, text: String): Long {
        val pageId = db.pageDao().insert(PageEntity(docId = docId, pageNumber = 0))
        db.pageTextDao().insert(
            PageTextEntity(pageId = pageId, text = text, textNorm = TextNormalizer.normalize(text))
        )
        return pageId
    }

    // --- library surfaces exclude ephemeral docs ---

    // @spec LIB-EXT-003
    @Test
    fun librarySurfaces_neverSeeEphemeralDocs() = runTest {
        insertLibraryDoc("lib.pdf", lastOpenedAt = 100)
        val ephId = insertEphemeralDoc("eph.pdf", expiresAt = 9_999, lastOpenedAt = 200)
        db.pageDao().insert(PageEntity(docId = ephId, pageNumber = 0))

        assertEquals(listOf("lib.pdf"), db.documentDao().observeAll().first().map { it.filename })
        assertEquals(1, db.documentDao().countIndexed())
        assertEquals(1, db.documentDao().observeIndexedCount().first())
        assertEquals(
            listOf("lib.pdf"),
            db.documentDao().observeRecentlyOpened(8).first().map { it.filename },
        )
        // No ghost empty-string folder row from the ephemeral doc.
        assertEquals(
            listOf(tree),
            db.documentDao().observeFolderStats().first().map { it.treeUri },
        )
        assertTrue(db.documentDao().getPendingOrError().isEmpty())
    }

    // markOpened must miss ephemeral rows so the caller's 0-rows contract still
    // routes the open into external_opens (where its recency lives).
    // @spec LIB-EXT-003
    @Test
    fun markOpened_missesEphemeralRows() = runTest {
        insertEphemeralDoc("eph.pdf", expiresAt = 9_999)

        val updated = db.documentDao().markOpened("content://ext/eph.pdf", 42L)

        assertEquals(0, updated)
        assertNull(db.documentDao().getByUri("content://ext/eph.pdf")!!.lastOpenedAt)
    }

    // Scan scoping: vanished-doc cleanup and folder removal are structurally
    // unable to touch ephemeral docs.
    // @spec LIB-EXT-003
    @Test
    fun treeScopedQueries_neverTouchEphemeralDocs() = runTest {
        insertLibraryDoc("lib.pdf")
        insertEphemeralDoc("eph.pdf", expiresAt = 9_999)

        assertEquals(
            listOf("$tree/document/primary%3ADocs%2Flib.pdf"),
            db.documentDao().idUrisByTreeUri(tree).map { it.uri },
        )

        db.documentDao().deleteByTreeUri(tree)

        assertNotNull(db.documentDao().getByUri("content://ext/eph.pdf"))
        assertNull(db.documentDao().getByUri("$tree/document/primary%3ADocs%2Flib.pdf"))
    }

    // --- search surfaces keep live ephemeral docs ---

    // @spec LIB-EXT-003
    @Test
    fun searchLanes_includeLiveEphemeralDocs() = runTest {
        val ephId = insertEphemeralDoc("boarding-pass.pdf", expiresAt = 9_999)
        insertPageWithText(ephId, "gate B42 boarding at noon")

        // Filename lane.
        assertEquals(
            listOf("boarding-pass.pdf"),
            db.documentDao().indexedFilenameRows(0, emptyList(), 0L).map { it.filename },
        )

        // Content lane.
        val hits = db.pageTextDao().searchPages(
            query = "boarding",
            filterByFolder = 0,
            treeUris = emptyList(),
            ocrOnly = 0,
            minIndexedAt = 0L,
            limit = 10,
        )
        assertEquals(listOf("boarding-pass.pdf"), hits.map { it.filename })

        // An active folder filter naturally excludes them (they have no folder).
        assertTrue(db.documentDao().indexedFilenameRows(1, listOf(tree), 0L).isEmpty())
    }

    // --- lifecycle ---

    // Rolling TTL refresh — conditional: it reports the affected count and can
    // never stamp a permanent library row.
    // @spec LIB-EXT-004
    @Test
    fun setEphemeralExpiry_movesTheDeadline() = runTest {
        insertEphemeralDoc("eph.pdf", expiresAt = 1_000)

        assertEquals(1, db.documentDao().setEphemeralExpiry("content://ext/eph.pdf", 2_000))

        assertEquals(2_000L, db.documentDao().getByUri("content://ext/eph.pdf")!!.ephemeralExpiresAt)
    }

    // The purge-race guard: a row reaped between a caller's read and the bump
    // reports 0 (the caller re-enqueues), and a permanent row is never demoted.
    // @spec LIB-EXT-004
    @Test
    fun setEphemeralExpiry_refusesMissingAndPermanentRows() = runTest {
        assertEquals(0, db.documentDao().setEphemeralExpiry("content://ext/gone.pdf", 2_000))

        insertLibraryDoc("lib.pdf")
        val libUri = "$tree/document/primary%3ADocs%2Flib.pdf"
        assertEquals(0, db.documentDao().setEphemeralExpiry(libUri, 2_000))
        assertNull(db.documentDao().getByUri(libUri)!!.ephemeralExpiresAt)
    }

    // The purge deletes only expired ephemeral rows — pages and searchability go
    // with them — and never touches permanent docs or still-live ephemerals.
    // @spec LIB-EXT-004
    @Test
    fun purgeExpiredEphemeral_deletesOnlyExpiredRows_cascadingTheirIndex() = runTest {
        insertLibraryDoc("lib.pdf")
        val liveId = insertEphemeralDoc("live.pdf", expiresAt = 2_000)
        val deadId = insertEphemeralDoc("dead.pdf", expiresAt = 500)
        insertPageWithText(liveId, "still searchable content")
        insertPageWithText(deadId, "vanishing content")

        val purged = db.documentDao().purgeExpiredEphemeral(now = 1_000)

        assertEquals(1, purged)
        assertNull(db.documentDao().getByUri("content://ext/dead.pdf"))
        assertNotNull(db.documentDao().getByUri("content://ext/live.pdf"))
        assertNotNull(db.documentDao().getByUri("$tree/document/primary%3ADocs%2Flib.pdf"))
        // Index rows cascade: the dead doc no longer matches, the live one does.
        val hits = db.pageTextDao().searchPages("content", 0, emptyList(), 0, 0L, 10)
        assertEquals(listOf("live.pdf"), hits.map { it.filename })
    }

    // Bookmarks are URI-keyed user data with no FK into documents — the TTL
    // purge must leave them untouched. (Reading positions live in DataStore,
    // outside the database entirely.)
    // @spec LIB-EXT-004
    @Test
    fun purgeExpiredEphemeral_sparesBookmarks() = runTest {
        insertEphemeralDoc("dead.pdf", expiresAt = 500)
        db.bookmarkDao().insert(
            BookmarkEntity(docUri = "content://ext/dead.pdf", pageNumber = 3, createdAt = 1L)
        )

        db.documentDao().purgeExpiredEphemeral(now = 1_000)

        assertNull(db.documentDao().getByUri("content://ext/dead.pdf"))
        assertEquals(
            1,
            db.bookmarkDao().observeForDocument("content://ext/dead.pdf").first().size,
        )
    }
}
