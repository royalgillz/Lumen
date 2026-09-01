package com.lumen.app.data.text

/**
 * Shared parsing of double-quoted phrase segments in a raw query. Both the
 * FTS MATCH expression builder and the NormalizedMatcher segment through
 * here, so "what counts as a quoted phrase" cannot drift between the index
 * query and the user-visible highlights.
 */
// @spec SEARCH-QRY-008, SEARCH-QRY-009
object QueryQuotes {

    data class Segment(val text: String, val quoted: Boolean)

    /**
     * Splits [input] on `"` characters, pairing them left to right. An
     * unbalanced trailing quote is dropped and everything after it degrades
     * to unquoted — the state a live query passes through mid-typing.
     */
    fun segments(input: String): List<Segment> {
        val segments = mutableListOf<Segment>()
        var start = 0
        var inQuote = false
        for (i in input.indices) {
            if (input[i] == '"') {
                if (i > start) segments.add(Segment(input.substring(start, i), inQuote))
                inQuote = !inQuote
                start = i + 1
            }
        }
        if (start < input.length) segments.add(Segment(input.substring(start), false))
        return segments
    }
}
