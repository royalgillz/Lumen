package com.lumen.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumen.app.data.db.entity.DocumentEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentDaoTest {

    private lateinit var db: LumenDatabase

    // Sibling trees where one id is a string prefix of the other — the exact
    // pair a prefix match over document URIs conflates.
    private val reportsTree =
        "content://com.android.externalstorage.documents/tree/primary%3AReports"
    private val reports2Tree =
        "content://com.android.externalstorage.documents/tree/primary%3AReports2"

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, LumenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed(treeUri: String, filename: String) {
        db.documentDao().upsert(
            DocumentEntity(
                uri = "$treeUri/document/${filename.replace(".", "%2E")}",
                filename = filename,
                treeUri = treeUri,
                status = "indexed",
            )
        )
    }

    // Removing Reports must not touch Reports2's documents.
    // @spec LIB-IDX-001
    @Test
    fun deleteByTreeUri_sparesSiblingFolderWithPrefixName() = runTest {
        seed(reportsTree, "a.pdf")
        seed(reportsTree, "b.pdf")
        seed(reports2Tree, "keep.pdf")

        db.documentDao().deleteByTreeUri(reportsTree)

        val remaining = db.documentDao().observeAll().first()
        assertEquals(listOf("keep.pdf"), remaining.map { it.filename })
        assertEquals(listOf(reports2Tree), remaining.map { it.treeUri })
    }

    // @spec LIB-IDX-001
    @Test
    fun deleteByTreeUri_removesAllDocumentsOfExactlyThatTree() = runTest {
        seed(reportsTree, "a.pdf")
        seed(reports2Tree, "x.pdf")
        seed(reports2Tree, "y.pdf")

        db.documentDao().deleteByTreeUri(reports2Tree)

        val remaining = db.documentDao().observeAll().first()
        assertEquals(listOf("a.pdf"), remaining.map { it.filename })
    }

    // @spec LIB-TTL-012
    @Test
    fun updateDerivedTitleAndAuthor_writesBothColumns() = runTest {
        seed(reportsTree, "a.pdf")
        val doc = db.documentDao().observeAll().first().single()

        db.documentDao().updateDerivedTitleAndAuthor(doc.id, "Visa Approval Notice", "Jane Doe")

        val updated = db.documentDao().getById(doc.id)!!
        assertEquals("Visa Approval Notice", updated.derivedTitle)
        assertEquals("Jane Doe", updated.author)
    }

    // The backfill's write fills only never-attempted rows: a title the
    // indexer landed after the backfill took its work list stays.
    // @spec LIB-TTL-013
    @Test
    fun updateDerivedTitleIfUnset_neverOverwritesAnAttemptedTitle() = runTest {
        seed(reportsTree, "a.pdf")
        val doc = db.documentDao().observeAll().first().single()

        db.documentDao().updateDerivedTitleIfUnset(doc.id, "Backfill Guess")
        assertEquals("Backfill Guess", db.documentDao().getById(doc.id)!!.derivedTitle)

        db.documentDao().updateDerivedTitleAndAuthor(doc.id, "Metadata Title", null)
        db.documentDao().updateDerivedTitleIfUnset(doc.id, "Late Backfill Guess")
        assertEquals("Metadata Title", db.documentDao().getById(doc.id)!!.derivedTitle)
    }
}
