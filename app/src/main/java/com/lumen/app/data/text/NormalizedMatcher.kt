package com.lumen.app.data.text

import java.text.Normalizer

/**
 * Locates query occurrences in *original* text with the same semantics the FTS
 * index uses: token-start anchoring, prefix extension, unicode61-equivalent
 * folding (lowercase + diacritic stripping), skipping normalization-deleted
 * characters. Every user-visible match — snippet spans, viewer highlight
 * rects, in-document search — comes from this matcher, so the surfaces agree.
 */
// @spec SEARCH-MATCH-001, SEARCH-MATCH-002, SEARCH-MATCH-003
object NormalizedMatcher {

    /**
     * Spans (indices into [original]) of every occurrence of every token of
     * [rawQuery], in document order. A span covers the full original-text
     * range of the matched characters, including deleted punctuation and
     * line-wrap hyphens the match crossed.
     */
    fun findMatches(original: String, rawQuery: String): List<IntRange> {
        val tokens = tokensOf(rawQuery)
        if (tokens.isEmpty() || original.isEmpty()) return emptyList()

        val results = mutableListOf<IntRange>()
        var i = 0
        var prevWasTokenChar = false
        while (i < original.length) {
            val len = spanLengthAt(original, i)
            val kind = kindAt(original, i)
            if (kind == Kind.TOKEN && !prevWasTokenChar) {
                for (token in tokens) {
                    matchAt(original, i, token)?.let { end -> results.add(i..end) }
                }
            }
            if (kind != Kind.TRANSPARENT) prevWasTokenChar = kind == Kind.TOKEN
            i += len
        }
        return results.distinct().sortedBy { it.first }
    }

    /** Folded tokens of [rawQuery], tokenized exactly as text is scanned. */
    fun tokensOf(rawQuery: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < rawQuery.length) {
            val len = spanLengthAt(rawQuery, i)
            when (kindAt(rawQuery, i)) {
                Kind.TOKEN -> current.append(fold(rawQuery[i]))
                Kind.SEPARATOR -> if (current.isNotEmpty()) {
                    tokens.add(current.toString()); current.clear()
                }
                Kind.TRANSPARENT -> Unit
            }
            i += len
        }
        if (current.isNotEmpty()) tokens.add(current.toString())
        return tokens.distinct()
    }

    /**
     * Matches [token] anchored at [start] (which must be a token character),
     * extending by prefix. Returns the inclusive end index, or null.
     */
    private fun matchAt(original: String, start: Int, token: String): Int? {
        var i = start
        var k = 0
        while (k < token.length) {
            if (i >= original.length) return null
            val len = spanLengthAt(original, i)
            when (kindAt(original, i)) {
                Kind.TRANSPARENT -> i += len
                Kind.SEPARATOR -> return null
                Kind.TOKEN -> {
                    for (f in fold(original[i])) {
                        if (k < token.length) {
                            if (f != token[k]) return null
                            k++
                        }
                    }
                    i += len
                }
            }
        }
        return i - 1
    }

    private enum class Kind { TOKEN, TRANSPARENT, SEPARATOR }

    /** A line-wrap hyphen consumes its newline; everything else is one char. */
    private fun spanLengthAt(s: String, i: Int): Int {
        if (s[i] == '-') {
            if (i + 2 < s.length && s[i + 1] == '\r' && s[i + 2] == '\n') return 3
            if (i + 1 < s.length && s[i + 1] == '\n') return 2
        }
        return 1
    }

    private fun kindAt(s: String, i: Int): Kind {
        val c = s[i]
        if (c == '-' || TextNormalizer.isDeleted(c)) return Kind.TRANSPARENT
        val folded = fold(c)
        if (folded.isEmpty()) return Kind.TRANSPARENT // bare combining mark
        return if (folded.all { it.isLetterOrDigit() }) Kind.TOKEN else Kind.SEPARATOR
    }

    /** unicode61(remove_diacritics=1)-equivalent per-char folding. */
    private fun fold(c: Char): String {
        if (c.code < 128) return c.lowercaseChar().toString()
        val decomposed = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) {
                sb.append(ch.lowercaseChar())
            }
        }
        return sb.toString()
    }
}
