package com.lumen.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumen.app.data.db.entity.DocumentEntity
import com.lumen.app.data.db.entity.PageEntity
import com.lumen.app.data.db.entity.PageTextEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageTextDaoTest {

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

    private suspend fun insertDoc(
        filename: String,
        status: String = "indexed",
        treeUri: String = "content://tree/primary",
    ): Long =
        db.documentDao().upsert(
            DocumentEntity(
                uri = "content://test/$filename",
                filename = filename,
                status = status,
                treeUri = treeUri,
            )
        )

    private suspend fun insertPage(docId: Long, pageNumber: Int = 0, isOcr: Boolean = false): Long =
        db.pageDao().insert(PageEntity(docId = docId, pageNumber = pageNumber, isOcr = isOcr))

    private suspend fun insertText(pageId: Long, text: String) =
        db.pageTextDao().insert(PageTextEntity(pageId = pageId, text = text))

    private suspend fun search(query: String, limit: Int = 201) =
        db.pageTextDao().searchPages(
            query = query,
            filterByFolder = 0,
            treeUris = emptyList(),
            ocrOnly = 0,
            minIndexedAt = 0L,
            limit = limit,
        )

    // --- tests ---

    @Test
    fun search_matchesIndexedDocument() = runTest {
        val pageId = insertPage(insertDoc("test.pdf"))
        insertText(pageId, "The quick brown fox jumps over the lazy dog")

        val results = search("\"quick brown\"")

        assertEquals(1, results.size)
        assertEquals("test.pdf", results[0].filename)
    }

    @Test
    fun search_matchesWordsAcrossLineBreaks() = runTest {
        val pageId = insertPage(insertDoc("test.pdf"))
        insertText(pageId, "climate report line one\npolicy details line two")

        val results = search("climate* AND policy*")

        assertEquals(1, results.size)
    }

    @Test
    fun search_noResults_forNonMatchingQuery() = runTest {
        val pageId = insertPage(insertDoc("test.pdf"))
        insertText(pageId, "The quick brown fox")

        val results = search("\"lorem ipsum\"")

        assertTrue(results.isEmpty())
    }

    @Test
    fun search_excludesPendingDocuments() = runTest {
        val pageId = insertPage(insertDoc("pending.pdf", status = "pending"))
        insertText(pageId, "searchable content in pending doc")

        val results = search("\"searchable content\"")

        assertTrue(results.isEmpty())
    }

    @Test
    fun search_returnsCorrectPageNumber() = runTest {
        val pageId = insertPage(insertDoc("test.pdf"), pageNumber = 4)
        insertText(pageId, "important finding on this page")

        val results = search("\"important finding\"")

        assertEquals(1, results.size)
        assertEquals(4, results[0].pageNumber)
    }

    @Test
    fun search_acrossMultipleDocuments() = runTest {
        val pageId1 = insertPage(insertDoc("alpha.pdf"))
        val pageId2 = insertPage(insertDoc("beta.pdf"))
        insertText(pageId1, "machine learning algorithms")
        insertText(pageId2, "machine learning models")

        val results = search("\"machine learning\"")

        assertEquals(2, results.size)
        assertEquals(setOf("alpha.pdf", "beta.pdf"), results.map { it.filename }.toSet())
    }

    @Test
    fun search_respectsLimit() = runTest {
        val docId = insertDoc("big.pdf")
        repeat(10) { i ->
            val pageId = insertPage(docId, pageNumber = i)
            insertText(pageId, "repeated phrase matches here")
        }

        val results = search("\"repeated phrase\"", limit = 5)

        assertEquals(5, results.size)
    }

    @Test
    fun search_folderFilter_appliesBeforeLimit() = runTest {
        val pageA = insertPage(insertDoc("a.pdf", treeUri = "content://tree/A"))
        val pageB = insertPage(insertDoc("b.pdf", treeUri = "content://tree/B"))
        insertText(pageA, "shared term")
        insertText(pageB, "shared term")

        val results = db.pageTextDao().searchPages(
            query = "shared*",
            filterByFolder = 1,
            treeUris = listOf("content://tree/B"),
            ocrOnly = 0,
            minIndexedAt = 0L,
            limit = 201,
        )

        assertEquals(1, results.size)
        assertEquals("b.pdf", results[0].filename)
    }

    @Test
    fun search_ocrOnlyFilter() = runTest {
        val docId = insertDoc("mix.pdf")
        val textPage = insertPage(docId, pageNumber = 0, isOcr = false)
        val ocrPage = insertPage(docId, pageNumber = 1, isOcr = true)
        insertText(textPage, "shared token")
        insertText(ocrPage, "shared token")

        val results = db.pageTextDao().searchPages(
            query = "shared*",
            filterByFolder = 0,
            treeUris = emptyList(),
            ocrOnly = 1,
            minIndexedAt = 0L,
            limit = 201,
        )

        assertEquals(1, results.size)
        assertTrue(results[0].isOcr)
    }
}
