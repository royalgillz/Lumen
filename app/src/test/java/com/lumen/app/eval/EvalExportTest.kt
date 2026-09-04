package com.lumen.app.eval

import com.lumen.app.domain.model.ScorerVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalExportTest {

    private fun row(variant: ScorerVariant, tag: String) =
        ScorecardRow(variant, tag, resolved = 4, hitsAt10 = 2, hitsAt20 = 3, mrr = 0.5, medianLatencyMs = 25)

    // @spec SEARCH-EVAL-008
    @Test
    fun `stamp line carries the indexed count and the newest indexedAt date`() {
        val line = EvalExport.stampLine(42, 1_756_000_000_000L)
        assertTrue(line.startsWith("42 docs indexed · newest 2025-08"))
        assertEquals("0 docs indexed · newest never", EvalExport.stampLine(0, null))
    }

    // @spec SEARCH-EVAL-009
    @Test
    fun `export is deterministic - variant then overall-first then tags alphabetical`() {
        val rows = listOf(
            row(ScorerVariant.CURRENT, "typo"),
            row(ScorerVariant.BM25, EvalScorer.OVERALL),
            row(ScorerVariant.BM25, "identifier"),
            row(ScorerVariant.CURRENT, EvalScorer.OVERALL),
        )
        val text = EvalExport.scorecardText(rows, "stamp", unresolvable = 1, errors = 0, versionName = "1.3")
        val dataLines = text.lines().filter { it.contains(" | ") && !it.startsWith("variant") }
        assertEquals(
            listOf(
                "BM25 | overall", "BM25 | identifier",
                "CURRENT | overall", "CURRENT | typo",
            ),
            dataLines.map { it.split(" | ").take(2).joinToString(" | ") },
        )
        assertTrue(text.startsWith("Lumen search eval · v1.3 · stamp"))
        assertTrue(text.contains("1 unresolvable entries excluded"))
        assertTrue(text.contains("0.500")) // Locale.US decimal point, never a comma
    }
}
