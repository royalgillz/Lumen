package com.lumen.app.data.text

/**
 * The single definition of Lumen's search-text normalization: line-wrap
 * de-hyphenation followed by deletion of the punctuation set P. The FTS index
 * (`page_text.textNorm`), the query sanitizer, the filename matcher, and the
 * viewer's occurrence matcher all normalize through here — symmetry between
 * index and query is what makes `F1` ≈ `F-1` ≈ `F.1` structural.
 *
 * The 8→9 migration backfills `textNorm` in SQL; [sqlNormExpr] generates that
 * `replace()` chain from the same character set so Kotlin and SQL cannot drift.
 * [normalize] is deliberately implemented as the identical sequence of
 * single-pass replacements — not a hand-rolled scanner — so equivalence holds
 * by construction, not by test luck.
 */
// @spec SEARCH-NORM-001, SEARCH-NORM-002
object TextNormalizer {

    /** Punctuation deleted wherever it appears. `/` and `_` are deliberately
     *  absent: deleting them merges tokens users search separately (dates,
     *  snake_case names). */
    private const val SET_P = "-‐–—.'’,:"

    /** Deleted before the per-character pass so `COVID-\n19` → `COVID19`. */
    private val LINE_WRAP_SEQUENCES = listOf("-\r\n", "-\n")

    /** True when [c] would be deleted by [normalize] (transparent to matching). */
    fun isDeleted(c: Char): Boolean = SET_P.indexOf(c) >= 0

    /** Normalizes [input] per SEARCH-NORM-001. Case and whitespace are preserved. */
    fun normalize(input: String): String {
        var s = input
        for (seq in LINE_WRAP_SEQUENCES) s = s.replace(seq, "")
        for (c in SET_P) s = s.replace(c.toString(), "")
        return s
    }

    /**
     * SQL expression producing the same result as [normalize] applied to
     * [column] — the same replacements in the same order.
     */
    fun sqlNormExpr(column: String): String {
        var expr = column
        expr = "replace($expr, '-' || char(13) || char(10), '')"
        expr = "replace($expr, '-' || char(10), '')"
        for (c in SET_P) {
            val literal = if (c == '\'') "''" else c.toString()
            expr = "replace($expr, '$literal', '')"
        }
        return expr
    }
}
