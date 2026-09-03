package com.lumen.app.eval

import com.lumen.app.domain.model.ScorerVariant

/** One line of the developer-authored evaluation set. */
data class EvalEntry(
    val query: String,
    val expectedFile: String,
    /** 1-indexed display page ("p. 3" on screen = 3); null = any row of the file counts. */
    val expectedPage: Int?,
    val tag: String,
)

/** Parse output: the valid entries plus per-entry problems, reported by index. */
data class EvalParse(
    val entries: List<EvalEntry>,
    val invalid: List<String>,
)

enum class EvalBucket {
    HIT,
    MISS,

    /** expectedFile matches no indexed document — set staleness, excluded from rates. */
    UNRESOLVABLE,

    /** the search threw — excluded from rates, run continues. */
    ERROR,
}

data class EntryResult(
    val entry: EvalEntry,
    val variant: ScorerVariant,
    val bucket: EvalBucket,
    /** 1-based rank of the first hitting row; HIT only. */
    val rank: Int?,
    val latencyMs: Long,
)

/** One scorecard line: a (variant, tag) cell. Counts are exact — never rounded away. */
data class ScorecardRow(
    val variant: ScorerVariant,
    val tag: String,
    val resolved: Int,
    val hitsAt10: Int,
    val hitsAt20: Int,
    val mrr: Double,
    val medianLatencyMs: Long,
)
