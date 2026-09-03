package com.lumen.app.eval

import com.lumen.app.domain.model.ScorerVariant
import com.lumen.app.domain.model.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EvalScorerTest {

    private fun result(
        filename: String,
        pageNumber: Int,
        isFilenameMatch: Boolean = false,
        isNoteMatch: Boolean = false,
    ) = SearchResult(
        lineId = 1L,
        docId = 1L,
        uri = "content://doc/$filename",
        filename = filename,
        displayTitle = filename,
        pageNumber = pageNumber,
        lineNumber = 0,
        snippet = "",
        isOcr = false,
        folderName = "f",
        isFilenameMatch = isFilenameMatch,
        isNoteMatch = isNoteMatch,
    )

    private fun entry(query: String = "q", file: String = "a.pdf", page: Int? = null, tag: String = "t") =
        EvalEntry(query = query, expectedFile = file, expectedPage = page, tag = tag)

    // ── hit rule ─────────────────────────────────────────────────────────────

    // @spec SEARCH-EVAL-005
    @Test
    fun `expectedPage is 1-indexed - display page 3 hits internal page 2`() {
        val results = listOf(result("a.pdf", pageNumber = 2))
        assertEquals(1, EvalScorer.rankOfFirstHit(results, entry(file = "a.pdf", page = 3)))
        assertNull(EvalScorer.rankOfFirstHit(results, entry(file = "a.pdf", page = 2)))
    }

    // @spec SEARCH-EVAL-005
    @Test
    fun `filename-lane rows never hit page-targeted entries - the page-0 trap`() {
        // Filename row always carries internal page 0; expectedPage 1 (display) maps to 0.
        val results = listOf(
            result("a.pdf", pageNumber = 0, isFilenameMatch = true),  // rank 1 — must NOT hit
            result("other.pdf", pageNumber = 4),                       // rank 2 — wrong file
            result("a.pdf", pageNumber = 0),                           // rank 3 — real content hit
        )
        assertEquals(3, EvalScorer.rankOfFirstHit(results, entry(file = "a.pdf", page = 1)))
    }

    // @spec SEARCH-EVAL-005
    @Test
    fun `document-level entries accept any row of the file including filename rows`() {
        val results = listOf(
            result("other.pdf", pageNumber = 1),
            result("a.pdf", pageNumber = 0, isFilenameMatch = true),
        )
        assertEquals(2, EvalScorer.rankOfFirstHit(results, entry(file = "a.pdf", page = null)))
    }

    // @spec SEARCH-EVAL-005
    @Test
    fun `note rows hit page-targeted entries and filename match is case-insensitive`() {
        val results = listOf(result("A.PDF", pageNumber = 4, isNoteMatch = true))
        assertEquals(1, EvalScorer.rankOfFirstHit(results, entry(file = "a.pdf", page = 5)))
        assertNull(EvalScorer.rankOfFirstHit(emptyList(), entry(file = "a.pdf")))
    }

    // ── scorecard math ───────────────────────────────────────────────────────

    private fun er(
        tag: String,
        bucket: EvalBucket,
        rank: Int? = null,
        latency: Long = 10,
        variant: ScorerVariant = ScorerVariant.BM25,
    ) = EntryResult(entry(tag = tag), variant, bucket, rank, latency)

    // @spec SEARCH-EVAL-007
    @Test
    fun `scorecard computes exact counts mrr and median over resolved entries`() {
        val rows = EvalScorer.scorecard(
            listOf(
                er("p", EvalBucket.HIT, rank = 1, latency = 10),   // rr 1.0
                er("p", EvalBucket.HIT, rank = 12, latency = 30),  // rr 1/12; in @20 not @10
                er("p", EvalBucket.MISS, latency = 20),            // rr 0
                er("i", EvalBucket.HIT, rank = 2, latency = 40),   // rr 0.5
            )
        )
        val overall = rows.first { it.tag == EvalScorer.OVERALL && it.variant == ScorerVariant.BM25 }
        assertEquals(4, overall.resolved)
        assertEquals(2, overall.hitsAt10)
        assertEquals(3, overall.hitsAt20)
        assertEquals((1.0 + 1.0 / 12 + 0.0 + 0.5) / 4, overall.mrr, 1e-9)
        assertEquals(25L, overall.medianLatencyMs) // even count: mean of 20,30

        val p = rows.first { it.tag == "p" }
        assertEquals(3, p.resolved)
        assertEquals(1, p.hitsAt10)
    }

    // @spec SEARCH-EVAL-006
    @Test
    fun `unresolvable and error entries are excluded from every rate and mean`() {
        val rows = EvalScorer.scorecard(
            listOf(
                er("p", EvalBucket.HIT, rank = 1, latency = 10),
                er("p", EvalBucket.UNRESOLVABLE, latency = 0),
                er("p", EvalBucket.ERROR, latency = 999),
            )
        )
        val overall = rows.first { it.tag == EvalScorer.OVERALL }
        assertEquals(1, overall.resolved)
        assertEquals(1, overall.hitsAt10)
        assertEquals(1.0, overall.mrr, 1e-9)
        assertEquals(10L, overall.medianLatencyMs) // 0 and 999 never enter the median
    }

    // @spec SEARCH-EVAL-004
    @Test
    fun `variant order alternates by entry index`() {
        val variants = listOf(ScorerVariant.CURRENT, ScorerVariant.BM25)
        assertEquals(variants, EvalScorer.variantOrderFor(0, variants))
        assertEquals(variants.reversed(), EvalScorer.variantOrderFor(1, variants))
        assertEquals(variants, EvalScorer.variantOrderFor(2, variants))
    }
}
