package com.lumen.app.eval

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pure formatting for the scorecard's stamp line and share-sheet export. */
object EvalExport {

    /** "N docs indexed · newest 2026-09-04 18:12" — the corpus stamp. @spec SEARCH-EVAL-008 */
    fun stampLine(indexedCount: Int, newestIndexedAt: Long?): String {
        val date = newestIndexedAt?.let {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(it))
        } ?: "never"
        return "$indexedCount docs indexed · newest $date"
    }

    /** Deterministic plain-text scorecard. @spec SEARCH-EVAL-009 */
    fun scorecardText(
        rows: List<ScorecardRow>,
        stampLine: String,
        unresolvable: Int,
        errors: Int,
        versionName: String,
    ): String = buildString {
        appendLine("Lumen search eval · v$versionName · $stampLine")
        if (unresolvable > 0) appendLine("$unresolvable unresolvable entries excluded")
        if (errors > 0) appendLine("$errors entries errored")
        appendLine()
        appendLine("variant | tag | n | @10 | @20 | MRR | med ms")
        for (row in rows.sortedWith(sortOrder())) {
            appendLine(
                "${row.variant.name} | ${row.tag} | ${row.resolved} | " +
                    "${row.hitsAt10} | ${row.hitsAt20} | " +
                    "%.3f".format(Locale.US, row.mrr) + " | ${row.medianLatencyMs}"
            )
        }
    }

    /** Variant, then overall-first, then tags alphabetically — shared by the
     *  screen and the export so they can never order differently. */
    fun sortOrder(): Comparator<ScorecardRow> =
        compareBy({ it.variant.name }, { it.tag != EvalScorer.OVERALL }, { it.tag })
}
