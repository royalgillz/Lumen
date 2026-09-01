package com.lumen.app.data.text

/** Plain snippet text plus highlight ranges into it. Markup is applied at
 *  render time; copy/share uses [text] as-is. */
data class Snippet(val text: String, val highlights: List<IntRange>)

/**
 * Builds result snippets from original page text (never normalized text),
 * windowing ~18 words around the first matched span with ellipses. Replaces
 * SQL `snippet()`, which after the textNorm migration would display normalized
 * text ("80211" where the page says "802.11").
 */
// @spec SEARCH-SNIP-001, SEARCH-SNIP-002, SEARCH-MATCH-004
object SnippetBuilder {

    private const val ELLIPSIS = "…"

    /**
     * Windows around the first of [spans] (indices into [original]). With no
     * spans — FTS matched but the matcher found nothing (SEARCH-MATCH-004) —
     * returns a start-of-page window with no highlights; never signals "drop
     * the row".
     */
    fun build(original: String, spans: List<IntRange>, windowWords: Int = 18): Snippet {
        val words = wordRanges(original)
        if (words.isEmpty()) return Snippet("", emptyList())

        val sorted = mergeOverlapping(spans)
        var startWord: Int
        var endWord: Int
        if (sorted.isEmpty()) {
            startWord = 0
            endWord = minOf(words.lastIndex, windowWords - 1)
        } else {
            val anchor = words.indexOfFirst { it.last >= sorted.first().first }
                .let { if (it < 0) words.lastIndex else it }
            val half = (windowWords - 1) / 2
            startWord = anchor - half
            endWord = anchor + half
            if (startWord < 0) {
                endWord -= startWord
                startWord = 0
            }
            if (endWord > words.lastIndex) {
                startWord -= endWord - words.lastIndex
                endWord = words.lastIndex
            }
            startWord = startWord.coerceAtLeast(0)
        }

        val from = words[startWord].first
        val to = words[endWord].last
        val prefix = if (from > 0) ELLIPSIS else ""
        val suffix = if (to < original.length - 1) ELLIPSIS else ""
        val text = prefix + original.substring(from, to + 1) + suffix

        val offset = prefix.length - from
        val highlights = sorted.mapNotNull { span ->
            val s = maxOf(span.first, from)
            val e = minOf(span.last, to)
            if (s <= e) (s + offset)..(e + offset) else null
        }
        return Snippet(text, highlights)
    }

    /** Sorts spans and merges overlapping/contained ones (a query token that is
     *  a prefix of another matches the same word twice) so no character is
     *  highlighted — and at render time re-emitted — more than once. Adjacent
     *  but disjoint spans stay separate occurrences. */
    // @spec SEARCH-SNIP-004
    private fun mergeOverlapping(spans: List<IntRange>): List<IntRange> {
        if (spans.size < 2) return spans
        val sorted = spans.sortedWith(compareBy({ it.first }, { it.last }))
        val merged = mutableListOf(sorted[0])
        for (span in sorted.subList(1, sorted.size)) {
            val last = merged.last()
            if (span.first <= last.last) {
                if (span.last > last.last) merged[merged.lastIndex] = last.first..span.last
            } else {
                merged.add(span)
            }
        }
        return merged
    }

    /** Ranges of whitespace-delimited words. */
    private fun wordRanges(s: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var start = -1
        for (i in s.indices) {
            if (s[i].isWhitespace()) {
                if (start >= 0) ranges.add(start until i)
                start = -1
            } else if (start < 0) {
                start = i
            }
        }
        if (start >= 0) ranges.add(start until s.length)
        return ranges.map { it.first..it.last }
    }
}
