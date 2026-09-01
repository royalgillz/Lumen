package com.lumen.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumen.app.data.db.entity.BookmarkEntity
import com.lumen.app.data.db.entity.DocumentEntity
import com.lumen.app.data.db.entity.DocumentTitleEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RenameDaoTest {

    private lateinit var db: LumenDatabase

    private val oldUri = "content://tree/A/document/A%2Fold.pdf"
    private val newUri = "content://tree/A/document/A%2Fnew.pdf"

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, LumenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedDocument(): Long = db.documentDao().upsert(
        DocumentEntity(
            uri = oldUri,
            filename = "old.pdf",
            treeUri = "content://tree/A",
            status = "indexed",
            lastOpenedAt = 777L,
            derivedTitle = "Some Derived Heading",
        )
    )

    // @spec LIB-REN-002
    @Test
    fun applyFileRename_rekeysRowInPlace_noReindexNeeded() = runTest {
        val id = seedDocument()
        db.documentTitleDao().upsert(DocumentTitleEntity(docUri = oldUri, title = "My Custom Name"))
        db.bookmarkDao().insert(BookmarkEntity(docUri = oldUri, pageNumber = 4, note = null, createdAt = 1L))

        db.renameDao().applyFileRename(oldUri = oldUri, newUri = newUri, newFilename = "new.pdf")

        val doc = db.documentDao().getByUri(newUri)!!
        // Same row: id, recency, status all preserved — nothing to re-index.
        assertEquals(id, doc.id)
        assertEquals("new.pdf", doc.filename)
        assertEquals(777L, doc.lastOpenedAt)
        assertEquals("indexed", doc.status)
        // One truth: derivation reset to the attempted sentinel, custom title gone.
        assertEquals("", doc.derivedTitle)
        assertNull(db.documentDao().getByUri(oldUri))
        assertNull(db.documentTitleDao().getTitle(oldUri))
        assertNull(db.documentTitleDao().getTitle(newUri))
        // Bookmarks follow the document.
        assertEquals(
            listOf(4),
            db.bookmarkDao().observeForDocument(newUri).first().map { it.pageNumber },
        )
        assertTrue(db.bookmarkDao().observeForDocument(oldUri).first().isEmpty())
    }

    // A dead file previously occupied the target path: its leftovers must not
    // attach to (or collide with) the renamed document.
    // @spec LIB-REN-002
    @Test
    fun applyFileRename_purgesOrphansAtTargetPath() = runTest {
        seedDocument()
        // Ghost title + bookmark keyed by the NEW uri, from a long-gone file —
        // the bookmark shares pageNumber 4 with a live one to prove the unique
        // index (docUri, pageNumber) cannot abort the transaction.
        db.documentTitleDao().upsert(DocumentTitleEntity(docUri = newUri, title = "Ghost Title"))
        db.bookmarkDao().insert(BookmarkEntity(docUri = newUri, pageNumber = 4, note = "ghost", createdAt = 1L))
        db.bookmarkDao().insert(BookmarkEntity(docUri = oldUri, pageNumber = 4, note = "real", createdAt = 2L))
        // Stale documents row at the target path (file vanished, cleanup pending).
        db.documentDao().upsert(DocumentEntity(uri = newUri, filename = "new.pdf", treeUri = "content://tree/A"))

        db.renameDao().applyFileRename(oldUri = oldUri, newUri = newUri, newFilename = "new.pdf")

        val doc = db.documentDao().getByUri(newUri)!!
        assertEquals(777L, doc.lastOpenedAt)
        assertNull(db.documentTitleDao().getTitle(newUri))
        val bookmarks = db.bookmarkDao().observeForDocument(newUri).first()
        assertEquals(listOf("real"), bookmarks.map { it.note })
        // Exactly one documents row remains for either URI.
        val all = db.documentDao().observeAll().first()
        assertEquals(1, all.count { it.uri == newUri || it.uri == oldUri })
    }

    // A stable-ID provider renamed in place (same URI back): the row and its
    // bookmarks survive untouched — only the filename and the one-truth
    // clearing may change.
    // @spec LIB-REN-011
    @Test
    fun applyInPlaceRename_updatesFilenameOnly_neverPurgesOwnRows() = runTest {
        val id = seedDocument()
        db.documentTitleDao().upsert(DocumentTitleEntity(docUri = oldUri, title = "My Custom Name"))
        db.bookmarkDao().insert(BookmarkEntity(docUri = oldUri, pageNumber = 4, note = null, createdAt = 1L))

        db.renameDao().applyInPlaceRename(uri = oldUri, newFilename = "new.pdf")

        val doc = db.documentDao().getByUri(oldUri)!!
        assertEquals(id, doc.id)
        assertEquals("new.pdf", doc.filename)
        assertEquals(777L, doc.lastOpenedAt)
        assertEquals("indexed", doc.status)
        assertEquals("", doc.derivedTitle)
        assertNull(db.documentTitleDao().getTitle(oldUri))
        assertEquals(
            listOf(4),
            db.bookmarkDao().observeForDocument(oldUri).first().map { it.pageNumber },
        )
    }
}
