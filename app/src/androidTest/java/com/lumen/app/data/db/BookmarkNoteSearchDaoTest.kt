package com.lumen.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumen.app.data.db.entity.BookmarkEntity
import com.lumen.app.data.db.entity.DocumentEntity
import com.lumen.app.data.db.entity.DocumentTitleEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookmarkNoteSearchDaoTest {

    private lateinit var db: LumenDatabase

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, LumenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun doc(name: String, treeUri: String = "tree://a", indexedAt: Long? = 100L): Long =
        db.documentDao().upsert(
            DocumentEntity(
                uri = "content://doc/$name",
                filename = name,
                treeUri = treeUri,
                status = DocumentEntity.STATUS_INDEXED,
                indexedAt = indexedAt,
            )
        )

    private suspend fun note(name: String, page: Int, note: String?) {
        db.bookmarkDao().insert(
            BookmarkEntity(docUri = "content://doc/$name", pageNumber = page, note = note, createdAt = 1L)
        )
    }

    // @spec SEARCH-NOTE-001
    @Test
    fun noteSearchRows_returnsOnlyNonBlankNotesJoinedWithTheirDocument() = runTest {
        val docId = doc("a.pdf")
        note("a.pdf", 3, "invoice 4471 paid")
        note("a.pdf", 5, null)
        note("a.pdf", 7, "   ")

        val rows = db.bookmarkDao().noteSearchRows(0, emptyList(), 0L)

        assertEquals(1, rows.size)
        assertEquals("invoice 4471 paid", rows[0].note)
        assertEquals(3, rows[0].pageNumber)
        assertEquals(docId, rows[0].docId)
        assertEquals("a.pdf", rows[0].filename)
        assertEquals("tree://a", rows[0].treeUri)
    }

    // A note on a document that was never indexed into the library (external
    // open) has no documents row — the inner join excludes it by design.
    // @spec SEARCH-NOTE-001
    @Test
    fun noteSearchRows_orphanNoteWithoutDocumentRow_excluded() = runTest {
        note("never-indexed.pdf", 0, "some note text")

        assertTrue(db.bookmarkDao().noteSearchRows(0, emptyList(), 0L).isEmpty())
    }

    // @spec SEARCH-NOTE-001
    @Test
    fun noteSearchRows_folderFilterNarrowsByTreeUri() = runTest {
        doc("in.pdf", treeUri = "tree://a")
        doc("out.pdf", treeUri = "tree://b")
        note("in.pdf", 1, "note in folder a")
        note("out.pdf", 1, "note in folder b")

        val rows = db.bookmarkDao().noteSearchRows(1, listOf("tree://a"), 0L)

        assertEquals(listOf("in.pdf"), rows.map { it.filename })
    }

    // @spec SEARCH-NOTE-001
    @Test
    fun noteSearchRows_minIndexedAtFilterApplies() = runTest {
        doc("old.pdf", indexedAt = 50L)
        doc("new.pdf", indexedAt = 200L)
        note("old.pdf", 1, "old note")
        note("new.pdf", 1, "new note")

        val rows = db.bookmarkDao().noteSearchRows(0, emptyList(), 100L)

        assertEquals(listOf("new.pdf"), rows.map { it.filename })
    }

    // @spec SEARCH-NOTE-002
    @Test
    fun noteSearchRows_carriesTitleColumnsForDisplayTitleResolution() = runTest {
        doc("titled.pdf")
        db.documentTitleDao().upsert(DocumentTitleEntity("content://doc/titled.pdf", "My Rename"))
        note("titled.pdf", 2, "a note")
        doc("plain.pdf")
        note("plain.pdf", 4, "another note")

        val rows = db.bookmarkDao().noteSearchRows(0, emptyList(), 0L)

        assertEquals("My Rename", rows.first { it.filename == "titled.pdf" }.customTitle)
        assertNull(rows.first { it.filename == "plain.pdf" }.customTitle)
    }
}
