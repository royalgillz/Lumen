package com.lumen.app.eval

import com.lumen.app.domain.model.ScorerVariant
import com.lumen.app.domain.model.SearchResult

/** Pure scoring: the hit rule and the scorecard math. */
object EvalScorer {

    const val OVERALL = "overall"

    /**
     * 1-based rank of the first result row satisfying the entry, or null.
     * Page-targeted entries (expectedPage, 1-indexed display) match content and
     * note rows whose internal 0-indexed page equals expectedPage − 1 — never
     * filename-lane rows, which always carry page 0. Document-level entries
     * (no expectedPage) accept any row of the file. Filenames compare
     * case-insensitively.
     */
    // @spec SEARCH-EVAL-005
    fun rankOfFirstHit(results: List<SearchResult>, entry: EvalEntry): Int? {
        val wantedPage0 = entry.expectedPage?.minus(1)
        results.forEachIndexed { index, r ->
            if (!r.filename.equals(entry.expectedFile, ignoreCase = true)) return@forEachIndexed
            val hit = if (wantedPage0 == null) {
                true
            } else {
                !r.isFilenameMatch && r.pageNumber == wantedPage0
            }
            if (hit) return index + 1
        }
        return null
    }

    /** Alternates variant order by entry index so neither variant always runs
     *  second and inherits the other's cache warmth. */
    // @spec SEARCH-EVAL-004
    fun variantOrderFor(entryIndex: Int, variants: List<ScorerVariant>): List<ScorerVariant> =
        if (entryIndex % 2 == 0) variants else variants.reversed()

    /**
     * Aggregates run results into (variant × tag) rows plus a (variant ×
     * overall) row. Rates and means run over resolved entries only (HIT+MISS);
     * unresolvable and error buckets never influence a number.
     */
    // @spec SEARCH-EVAL-006, SEARCH-EVAL-007
    fun scorecard(results: List<EntryResult>): List<ScorecardRow> {
        val rows = mutableListOf<ScorecardRow>()
        for ((variant, ofVariant) in results.groupBy { it.variant }) {
            val tags = ofVariant.map { it.entry.tag }.distinct().sorted()
            for (tag in listOf(OVERALL) + tags) {
                val scoped = if (tag == OVERALL) ofVariant else ofVariant.filter { it.entry.tag == tag }
                val resolved = scoped.filter { it.bucket == EvalBucket.HIT || it.bucket == EvalBucket.MISS }
                if (resolved.isEmpty() && tag != OVERALL) continue
                val latencies = resolved.map { it.latencyMs }.sorted()
                rows += ScorecardRow(
                    variant = variant,
                    tag = tag,
                    resolved = resolved.size,
                    hitsAt10 = resolved.count { it.bucket == EvalBucket.HIT && it.rank!! <= 10 },
                    hitsAt20 = resolved.count { it.bucket == EvalBucket.HIT && it.rank!! <= 20 },
                    mrr = if (resolved.isEmpty()) 0.0 else {
                        resolved.sumOf { r -> r.rank?.let { 1.0 / it } ?: 0.0 } / resolved.size
                    },
                    medianLatencyMs = median(latencies),
                )
            }
        }
        return rows
    }

    private fun median(sorted: List<Long>): Long = when {
        sorted.isEmpty() -> 0L
        sorted.size % 2 == 1 -> sorted[sorted.size / 2]
        else -> (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }
}
