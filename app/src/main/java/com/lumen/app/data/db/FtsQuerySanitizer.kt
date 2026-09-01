package com.lumen.app.data.db

import com.lumen.app.data.text.NormalizedMatcher
import com.lumen.app.data.text.QueryQuotes
import com.lumen.app.data.text.TextNormalizer

// @spec SEARCH-QRY-001, SEARCH-QRY-002, SEARCH-QRY-003, SEARCH-QRY-005, SEARCH-QRY-007, SEARCH-QRY-008, SEARCH-QRY-009, SEARCH-QRY-010
object FtsQuerySanitizer {
    /**
     * Turns raw user input into a safe FTS4 MATCH expression against the
     * normalized index column: FTS metacharacters and non-P separators become
     * spaces, the remainder is TextNormalizer-normalized (so "f-1" → "f1"),
     * and each distinct token (case-insensitively) becomes "token*" joined by
     * a single space.
     *
     * A double-quoted segment with two or more tokens becomes an FTS4 phrase
     * of prefix terms ("invoice* 4471*") — adjacency, not implicit AND. Inner
     * tokens sanitize and normalize exactly like unquoted ones; prefix terms
     * inside a phrase are FTS4-only syntax, which is what the app requires
     * anyway. A quoted single token is identical to the plain token, and an
     * unbalanced quote is stripped (QueryQuotes degrades its remainder to
     * unquoted tokens).
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
        val phrases = parsePhrases(input)
        if (phrases.isEmpty()) return null
        if (phrases.none { phrase -> phrase.any { it.length >= 2 } }) return null
        // Index-identity dedupe per phrase: unicode61 folds case AND
        // diacritics, so "Visa visa" and "café cafe" are each one term to FTS
        // — emitting a term twice doubles every matchinfo hit total. The key
        // must therefore fold exactly as the index does, not just lowercase.
        // A quoted single token dedupes against the same unquoted token.
        return phrases
            .distinctBy { phrase -> phrase.joinToString(" ") { NormalizedMatcher.indexFold(it) } }
            .joinToString(" ") { phrase ->
                if (phrase.size == 1) "${phrase[0]}*"
                else phrase.joinToString(" ", prefix = "\"", postfix = "\"") { "$it*" }
            }
    }

    /**
     * The query as a list of normalized phrases: each balanced double-quoted
     * segment is one phrase (its tokens must be adjacent); each unquoted token
     * is its own singleton phrase. Order of appearance preserved, no dedupe —
     * shared with the filename lane so both lanes group tokens identically.
     */
    fun parsePhrases(input: String): List<List<String>> {
        val phrases = mutableListOf<List<String>>()
        for (segment in QueryQuotes.segments(input)) {
            val tokens = tokenize(segment.text)
            if (tokens.isEmpty()) continue
            if (segment.quoted) phrases.add(tokens)
            else tokens.forEach { phrases.add(listOf(it)) }
        }
        return phrases
    }

    /**
     * The normalized query tokens [sanitize] builds its expression from —
     * shared with callers that need the same gate or token list without the
     * FTS syntax (e.g. the viewer's in-document search). Flat: quotes act as
     * plain separators here.
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
