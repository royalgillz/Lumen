package com.lumen.app.data.repository

import com.lumen.app.domain.model.ScorerVariant
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.max

/**
 * Parsed FTS4 matchinfo('pcnalx') blob: the exact badge total and the BM25
 * inputs, from ONE parse — badge and ranking can never disagree about what
 * matched.
 */
data class MatchStats(
    /** Sum of tf over all phrase-columns — the per-page badge; never capped. */
    val totalHits: Int,
    /** x-section tf for column 0, one per phrase. */
    val phraseTf: IntArray,
    /** x-section df (rows with >=1 hit) for column 0, one per phrase. */
    val phraseDf: IntArray,
    /** n — rows in the FTS table. */
    val rowCount: Int,
    /** a[0] — average tokens per row (integer-truncated by SQLite). */
    val avgTokens: Double,
    /** l[0] — tokens on this page. */
    val docTokens: Int,
) {
    // IntArrays are internal plumbing; identity equality is fine.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** A row's sort inputs under RELEVANCE: score, then the tie-break chain. */
data class RankedRow(
    val score: Double,
    val rawHits: Int,
    val filename: String,
    val pageNumber: Int,
)

internal object SearchRanking {
    const val BM25_K1 = 1.2
    const val BM25_B = 0.75
    const val NAME_PHRASE_BOOST = 20.0

    /**
     * Parse an FTS4 matchinfo blob in 'pcnalx' format (sections in
     * format-string order: p, c, n, a[c], l[c], x[3·p·c]; 32-bit LE uints).
     * Honors the declared column count generically. Returns null on a
     * missing, truncated, or malformed blob — the caller keeps the row with
     * zero hits and score, matching the pre-existing null-blob path.
     *
     * The returned [MatchStats.totalHits] sums tf over ALL phrase-columns:
     * this exact total is what the per-page badge shows — never capped or
     * rounded.
     */
    // @spec SEARCH-RANK-002, SEARCH-RANK-004, SEARCH-CNT-001
    fun parse(blob: ByteArray?): MatchStats? {
        if (blob == null || blob.size < 12) return null
        return try {
            val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
            val p = buf.int
            val c = buf.int
            val n = buf.int
            if (p < 0 || c <= 0) return null
            if (buf.remaining() < 4 * (2 * c + 3 * p * c)) return null
            val a = IntArray(c) { buf.int }
            val l = IntArray(c) { buf.int }
            val tf = IntArray(p)
            val df = IntArray(p)
            var total = 0
            repeat(p) { phrase ->
                repeat(c) { col ->
                    val hits = buf.int
                    buf.int // global hit total — unused
                    val docs = buf.int
                    if (col == 0) {
                        tf[phrase] = hits
                        df[phrase] = docs
                    }
                    total += hits
                }
            }
            MatchStats(total, tf, df, n, a[0].toDouble(), l[0])
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Okapi BM25 over the page's single FTS column. IDF is clamped at zero so
     * a phrase present in more than half the index contributes nothing —
     * never a negative. Degenerate inputs (empty index, zero average length)
     * are guarded to 1 so the score stays finite and non-negative.
     */
    // @spec SEARCH-RANK-001, SEARCH-RANK-005
    fun bm25(stats: MatchStats): Double {
        val n = stats.rowCount.coerceAtLeast(1).toDouble()
        val avg = if (stats.avgTokens > 0.0) stats.avgTokens else 1.0
        val lenNorm = 1.0 - BM25_B + BM25_B * (stats.docTokens.toDouble() / avg)
        var score = 0.0
        for (i in stats.phraseTf.indices) {
            val tf = stats.phraseTf[i].toDouble()
            if (tf <= 0.0) continue
            val df = stats.phraseDf[i].toDouble()
            val idf = max(ln((n - df + 0.5) / (df + 0.5)), 0.0)
            score += idf * (tf * (BM25_K1 + 1.0)) / (tf + BM25_K1 * lenNorm)
        }
        return score
    }

    /**
     * A content row's relevance score under the given variant, plus
     * +[NAME_PHRASE_BOOST] per query phrase matching the document's name or
     * custom title — identical across variants, so switching scorers never
     * changes what a name match is worth relative to itself.
     */
    // @spec SEARCH-RANK-006, SEARCH-RANK-007
    fun score(stats: MatchStats?, nameMatchingPhrases: Int, variant: ScorerVariant): Double {
        val base = when (variant) {
            ScorerVariant.CURRENT -> (stats?.totalHits ?: 0).toDouble()
            ScorerVariant.BM25 -> stats?.let { bm25(it) } ?: 0.0
        }
        return base + nameMatchingPhrases * NAME_PHRASE_BOOST
    }

    /**
     * RELEVANCE ordering: score desc, then raw hits desc, then case-folded
     * filename asc, then page asc. The raw-hits tie-break is load-bearing:
     * the IDF floor zeroes all-common-term queries, which would otherwise
     * degrade to alphabetical order.
     */
    // @spec SEARCH-RANK-003
    fun orderComparator(): Comparator<RankedRow> =
        compareByDescending<RankedRow> { it.score }
            .thenByDescending { it.rawHits }
            .thenBy { it.filename.lowercase() }
            .thenBy { it.pageNumber }
}
