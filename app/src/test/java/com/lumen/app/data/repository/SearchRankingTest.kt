package com.lumen.app.data.repository

import com.lumen.app.domain.model.ScorerVariant
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRankingTest {

    /** Builds a matchinfo('pcnalx') blob: p, c, n, a[c], l[c], x[3·p·c] — 32-bit LE. */
    private fun blob(vararg ints: Int): ByteArray {
        val buf = ByteBuffer.allocate(ints.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        ints.forEach { buf.putInt(it) }
        return buf.array()
    }

    // ── parse ────────────────────────────────────────────────────────────────

    // @spec SEARCH-RANK-002
    @Test
    fun `parse extracts badge total and bm25 inputs from one blob`() {
        // p=2, c=1, n=100, a=[50], l=[40], x = [tf,global,df] per phrase
        val stats = SearchRanking.parse(
            blob(2, 1, 100, 50, 40, /*phrase0*/ 3, 30, 10, /*phrase1*/ 2, 5, 4)
        )!!
        assertEquals(5, stats.totalHits)                 // 3 + 2 — the exact badge
        assertEquals(listOf(3, 2), stats.phraseTf.toList())
        assertEquals(listOf(10, 4), stats.phraseDf.toList())
        assertEquals(100, stats.rowCount)
        assertEquals(50.0, stats.avgTokens, 0.0)
        assertEquals(40, stats.docTokens)
    }

    // @spec SEARCH-RANK-002
    @Test
    fun `parse honors a declared second column without corrupting offsets`() {
        // p=1, c=2, n=10, a=[20,30], l=[15,25], x = col0 [2,8,3], col1 [4,9,5]
        val stats = SearchRanking.parse(
            blob(1, 2, 10, 20, 30, 15, 25, 2, 8, 3, 4, 9, 5)
        )!!
        assertEquals(6, stats.totalHits)                 // badge sums ALL phrase-columns: 2 + 4
        assertEquals(listOf(2), stats.phraseTf.toList()) // score inputs from column 0
        assertEquals(listOf(3), stats.phraseDf.toList())
        assertEquals(20.0, stats.avgTokens, 0.0)
        assertEquals(15, stats.docTokens)
    }

    // @spec SEARCH-RANK-004
    @Test
    fun `parse returns null for missing truncated or malformed blobs`() {
        assertNull(SearchRanking.parse(null))
        assertNull(SearchRanking.parse(ByteArray(0)))
        assertNull(SearchRanking.parse(ByteArray(8)))                    // header alone too short
        assertNull(SearchRanking.parse(blob(2, 1, 100, 50, 40, 3, 30))) // x-section truncated
        assertNull(SearchRanking.parse(blob(-1, 1, 100)))                // negative phrase count
        assertNull(SearchRanking.parse(blob(1, 0, 100)))                 // zero columns
    }

    // ── bm25 ─────────────────────────────────────────────────────────────────

    // @spec SEARCH-RANK-001
    @Test
    fun `bm25 matches the hand-computed okapi value`() {
        // n=100, a=50, l=40, one phrase: tf=2, df=10, k1=1.2, b=0.75
        val stats = SearchRanking.parse(blob(1, 1, 100, 50, 40, 2, 20, 10))!!
        val idf = ln((100 - 10 + 0.5) / (10 + 0.5))
        val lenNorm = 1 - 0.75 + 0.75 * (40.0 / 50.0)
        val expected = idf * (2 * (1.2 + 1)) / (2 + 1.2 * lenNorm)
        assertEquals(expected, SearchRanking.bm25(stats), 1e-9)
    }

    // @spec SEARCH-RANK-001
    @Test
    fun `idf is clamped at zero for phrases in over half the index`() {
        // df=60 of n=100 → raw idf negative → phrase contributes exactly 0
        val stats = SearchRanking.parse(blob(1, 1, 100, 50, 40, 5, 300, 60))!!
        assertEquals(0.0, SearchRanking.bm25(stats), 0.0)
    }

    // @spec SEARCH-RANK-005
    @Test
    fun `bm25 never yields NaN infinite or negative under degenerate inputs`() {
        // Empty index (n=0), zero average length, zero doc length, zero tf.
        for (b in listOf(
            blob(1, 1, 0, 0, 0, 0, 0, 0),
            blob(1, 1, 0, 0, 0, 3, 3, 1),
            blob(2, 1, 1, 0, 7, 1, 1, 1, 0, 0, 0),
        )) {
            val score = SearchRanking.bm25(SearchRanking.parse(b)!!)
            assertTrue("score=$score must be finite", score.isFinite())
            assertTrue("score=$score must be >= 0", score >= 0.0)
        }
    }

    // ── score (variant dispatch + name boost) ────────────────────────────────

    // @spec SEARCH-RANK-007
    @Test
    fun `CURRENT variant scores by raw hit total`() {
        val stats = SearchRanking.parse(blob(1, 1, 100, 50, 40, 7, 70, 10))!!
        assertEquals(7.0, SearchRanking.score(stats, 0, ScorerVariant.CURRENT), 0.0)
    }

    // @spec SEARCH-RANK-001
    @Test
    fun `BM25 variant scores by okapi`() {
        val stats = SearchRanking.parse(blob(1, 1, 100, 50, 40, 2, 20, 10))!!
        assertEquals(
            SearchRanking.bm25(stats),
            SearchRanking.score(stats, 0, ScorerVariant.BM25),
            1e-9,
        )
    }

    // @spec SEARCH-RANK-006
    @Test
    fun `name boost adds 20 per matching phrase under every variant`() {
        val stats = SearchRanking.parse(blob(1, 1, 100, 50, 40, 2, 20, 10))!!
        for (variant in ScorerVariant.entries) {
            val base = SearchRanking.score(stats, 0, variant)
            assertEquals(base + 40.0, SearchRanking.score(stats, 2, variant), 1e-9)
        }
    }

    // @spec SEARCH-RANK-004
    @Test
    fun `null stats score zero but keep the row rankable`() {
        assertEquals(0.0, SearchRanking.score(null, 0, ScorerVariant.BM25), 0.0)
        assertEquals(20.0, SearchRanking.score(null, 1, ScorerVariant.BM25), 0.0)
    }

    // ── tie-break ordering ───────────────────────────────────────────────────

    // @spec SEARCH-RANK-003
    @Test
    fun `equal scores break by raw hits then folded filename then page`() {
        val cmp = SearchRanking.orderComparator()
        val base = RankedRow(score = 0.0, rawHits = 3, filename = "b.pdf", pageNumber = 2)

        // Higher score first, regardless of hits.
        assertTrue(cmp.compare(base.copy(score = 1.0, rawHits = 0), base) < 0)
        // Equal score: more raw hits first (all-common-term queries stay ordered).
        assertTrue(cmp.compare(base.copy(rawHits = 9), base) < 0)
        // Equal score+hits: case-folded filename ascending.
        assertTrue(cmp.compare(base.copy(filename = "A.pdf"), base) < 0)
        assertTrue(cmp.compare(base.copy(filename = "a.pdf"), base.copy(filename = "B.pdf")) < 0)
        // Equal everything else: page ascending.
        assertTrue(cmp.compare(base.copy(pageNumber = 1), base) < 0)
    }
}
