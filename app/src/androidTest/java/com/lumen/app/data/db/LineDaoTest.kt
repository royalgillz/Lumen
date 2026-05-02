package com.lumen.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumen.app.data.db.entity.DocumentEntity
import com.lumen.app.data.db.entity.LineContentEntity
import com.lumen.app.data.db.entity.PageEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LineDaoTest {

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

    // --- helpers ---

    private suspend fun insertDoc(filename: String, status: String = "indexed"): Long =
        db.documentDao().upsert(
            DocumentEntity(uri = "content://test/$filename", filename = filename, status = status)
        )

    private suspend fun insertPage(docId: Long, pageNumber: Int = 0): Long =
        db.pageDao().insert(PageEntity(docId = docId, pageNumber = pageNumber))

    private suspend fun insertLine(pageId: Long, lineNumber: Int, text: String): Long =
        db.lineDao().insert(LineContentEntity(pageId = pageId, lineNumber = lineNumber, text = text))

    // --- tests ---

    @Test
    fun search_matchesIndexedDocument() = runTest {
        val pageId = insertPage(insertDoc("test.pdf"))
        insertLine(pageId, 0, "The quick brown fox jumps over the lazy dog")

        val results = db.lineDao().search("\"quick brown\"")

        assertEquals(1, results.size)
        assertEquals("test.pdf", results[0].filename)
    }

    @Test
    fun search_noResults_forNonMatchingQuery() = runTest {
        val pageId = insertPage(insertDoc("test.pdf"))
        insertLine(pageId, 0, "The quick brown fox")

        val results = db.lineDao().search("\"lorem ipsum\"")

        assertTrue(results.isEmpty())
    }

    @Test
    fun search_excludesPendingDocuments() = runTest {
        val pageId = insertPage(insertDoc("pending.pdf", status = "pending"))
        insertLine(pageId, 0, "searchable content in pending doc")

        val results = db.lineDao().search("\"searchable content\"")

        assertTrue(results.isEmpty())
    }

    @Test
    fun search_returnsCorrectPageNumber() = runTest {
        val pageId = insertPage(insertDoc("test.pdf"), pageNumber = 4)
        insertLine(pageId, 0, "important finding on this page")

        val results = db.lineDao().search("\"important finding\"")

        assertEquals(1, results.size)
        assertEquals(4, results[0].pageNumber)
    }

    @Test
    fun search_acrossMultipleDocuments() = runTest {
        val pageId1 = insertPage(insertDoc("alpha.pdf"))
        val pageId2 = insertPage(insertDoc("beta.pdf"))
        insertLine(pageId1, 0, "machine learning algorithms")
        insertLine(pageId2, 0, "machine learning models")

        val results = db.lineDao().search("\"machine learning\"")

        assertEquals(2, results.size)
        assertEquals(setOf("alpha.pdf", "beta.pdf"), results.map { it.filename }.toSet())
    }

    @Test
    fun search_respectsLimit() = runTest {
        val docId = insertDoc("big.pdf")
        repeat(10) { i ->
            val pageId = insertPage(docId, pageNumber = i)
            insertLine(pageId, 0, "repeated phrase matches here")
        }

        val results = db.lineDao().search("\"repeated phrase\"", limit = 5)

        assertEquals(5, results.size)
    }

    @Test
    fun search_singleWord_returnsMatch() = runTest {
        val pageId = insertPage(insertDoc("doc.pdf"))
        insertLine(pageId, 0, "Kotlin coroutines are powerful")

        val results = db.lineDao().search("\"Kotlin\"")

        assertEquals(1, results.size)
    }
}
