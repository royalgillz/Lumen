package com.lumen.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumen.app.data.db.entity.DocumentEntity
import com.lumen.app.data.db.entity.PageEntity
import com.lumen.app.data.db.entity.PageTextEntity
import com.lumen.app.data.repository.SearchRanking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the matchinfo('pcnalx') contract against real SQLite: the blob must
 * carry n/a/l plus exact per-phrase tf/df, and the viewer's in-document page
 * list must stay matchinfo-free and unaffected.
 */
@RunWith(AndroidJUnit4::class)
class PageTextDaoMatchinfoTest {

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

    private suspend fun page(docId: Long, pageNumber: Int, textNorm: String): Long {
        val pageId = db.pageDao().insert(PageEntity(docId = docId, pageNumber = pageNumber))
        db.pageTextDao().insert(PageTextEntity(pageId = pageId, text = textNorm, textNorm = textNorm))
        return pageId
    }

    private suspend fun doc(name: String): Long =
        db.documentDao().upsert(
            DocumentEntity(
                uri = "content://doc/$name",
                filename = name,
                treeUri = "tree://a",
                status = DocumentEntity.STATUS_INDEXED,
                indexedAt = 100L,
            )
        )

    private suspend fun search(query: String) = db.pageTextDao().searchPages(
        query = query,
        filterByFolder = 0,
        treeUris = emptyList(),
        ocrOnly = 0,
        minIndexedAt = 0,
        limit = 100,
    )

    // @spec SEARCH-RANK-002
    @Test
    fun searchPages_blob_carries_exact_pcnalx_stats() = runTest {
        val d = doc("a.pdf")
        // Corpus: 3 rows, token counts 3 / 1 / 4 → n=3, a = (3+1+4)/3 = 2 (integer-truncated).
        page(d, 0, "alpha beta alpha")
        page(d, 1, "alpha")
        page(d, 2, "gamma delta epsilon zeta")

        val rows = search("alpha*").sortedBy { it.pageNumber }
        assertEquals(2, rows.size)

        val p0 = SearchRanking.parse(rows[0].matchInfo)
        assertNotNull("blob must parse as 'pcnalx'", p0)
        assertEquals(2, p0!!.totalHits)          // "alpha" twice on page 0 — exact badge
        assertEquals(3, p0.rowCount)             // n = all FTS rows, not just matches
        assertEquals(2.0, p0.avgTokens, 0.0)     // a, integer-truncated by SQLite
        assertEquals(3, p0.docTokens)            // l for page 0
        assertEquals(listOf(2), p0.phraseTf.toList())
        assertEquals(listOf(2), p0.phraseDf.toList()) // df: 2 rows contain "alpha"

        val p1 = SearchRanking.parse(rows[1].matchInfo)!!
        assertEquals(1, p1.totalHits)
        assertEquals(1, p1.docTokens)
    }

    // @spec SEARCH-RANK-002
    @Test
    fun searchPages_blob_counts_phrases_per_phrase() = runTest {
        val d = doc("b.pdf")
        page(d, 0, "invoice 4471 total invoice")
        page(d, 1, "invoice")

        val rows = search("invoice* 4471*").sortedBy { it.pageNumber }
        assertEquals(1, rows.size) // implicit AND: only page 0 has both

        val stats = SearchRanking.parse(rows[0].matchInfo)!!
        assertEquals(3, stats.totalHits) // 2×invoice + 1×4471
        assertEquals(listOf(2, 1), stats.phraseTf.toList())
        assertEquals(listOf(2, 1), stats.phraseDf.toList())
    }

    // @spec SEARCH-RANK-009
    @Test
    fun searchPagesInDocument_stays_matchinfo_free_and_working() = runTest {
        val d = doc("c.pdf")
        page(d, 0, "alpha beta")
        page(d, 3, "beta alpha")
        page(d, 5, "gamma")

        val pages = db.pageTextDao().searchPagesInDocument("alpha*", "content://doc/c.pdf")
        assertEquals(listOf(0, 3), pages)
    }
}
