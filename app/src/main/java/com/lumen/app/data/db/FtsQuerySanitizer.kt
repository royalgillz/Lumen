package com.lumen.app.data.db

import com.lumen.app.data.text.TextNormalizer

// @spec SEARCH-QRY-001, SEARCH-QRY-002, SEARCH-QRY-003, SEARCH-QRY-005
object FtsQuerySanitizer {
    /**
     * Turns raw user input into a safe FTS4 MATCH expression against the
     * normalized index column: FTS metacharacters and non-P separators become
     * spaces, the remainder is TextNormalizer-normalized (so "f-1" → "f1"),
     * and each token becomes "token*" joined by a single space.
     *
     * The join MUST be a space, not " AND ": whether SQLite is compiled with the
     * enhanced FTS4 query syntax is device-dependent, and under the standard
     * syntax "AND" is a LITERAL search term — it would match (and highlight) the
     * word "and" in documents. A bare space means implicit AND under BOTH syntaxes.
     *
     * Returns null when no search should run: no usable tokens, or no token with
     * ≥ 2 normalized characters (a 1-char prefix term matches most of the index).
     */
    fun sanitize(input: String): String? {
        val tokens = tokenize(input)
        if (tokens.isEmpty()) return null
        if (tokens.none { it.length >= 2 }) return null
        return tokens.joinToString(" ") { "$it*" }
    }

    /**
     * The normalized query tokens [sanitize] builds its expression from —
     * shared with callers that need the same gate or token list without the
     * FTS syntax (e.g. the viewer's in-document search).
     */
    fun tokenize(input: String): List<String> =
        TextNormalizer.normalize(input.replace(SEPARATORS, " "))
            .trim()
            .split(WHITESPACE)
            .filter { it.isNotEmpty() }

    /** FTS metacharacters plus separators outside set P. */
    private val SEPARATORS = Regex("""["*()^/]""")
    private val WHITESPACE = Regex("\\s+")
}
