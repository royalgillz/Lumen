package com.lumen.app.data.text

import java.text.Normalizer

/**
 * Locates query occurrences in *original* text with the same semantics the FTS
 * index uses: token-start anchoring, prefix extension, unicode61-equivalent
 * folding (lowercase + diacritic stripping), skipping normalization-deleted
 * characters. A double-quoted phrase matches as ONE adjacent occurrence — a
 * single span — mirroring the FTS4 phrase term the sanitizer emits. Every
 * user-visible match — snippet spans, viewer highlight rects, in-document
 * search — comes from this matcher, so the surfaces agree.
 */
// @spec SEARCH-MATCH-001, SEARCH-MATCH-002, SEARCH-MATCH-003, SEARCH-MATCH-005
object NormalizedMatcher {

    /**
     * Spans (indices into [original]) of every occurrence of every phrase of
     * [rawQuery], in document order. A span covers the full original-text
     * range of the matched characters, including deleted punctuation and
     * line-wrap hyphens the match crossed; a multi-token quoted phrase yields
     * one span from its first token's start to its last token's matched end.
     */
    fun findMatches(original: String, rawQuery: String): List<IntRange> {
        val phrases = phrasesOf(rawQuery)
        if (phrases.isEmpty() || original.isEmpty()) return emptyList()

        val results = mutableListOf<IntRange>()
        var i = 0
        var prevWasTokenChar = false
        while (i < original.length) {
            val len = spanLengthAt(original, i)
            val kind = kindAt(original, i)
            if (kind == Kind.TOKEN && !prevWasTokenChar) {
                for (phrase in phrases) {
                    matchPhraseAt(original, i, phrase)?.let { end -> results.add(i..end) }
                }
            }
            if (kind != Kind.TRANSPARENT) prevWasTokenChar = kind == Kind.TOKEN
            i += len
        }
        return results.distinct().sortedBy { it.first }
    }

    /**
     * True when every phrase of [rawQuery] occurs at least once in [original]
     * — the implicit-AND semantics of the MATCH expression, applied to text
     * that lives outside the FTS index (bookmark notes).
     */
    // @spec SEARCH-NOTE-001
    fun matchesAllPhrases(original: String, rawQuery: String): Boolean {
        val phrases = phrasesOf(rawQuery)
        if (phrases.isEmpty() || original.isEmpty()) return false
        return phrases.all { hasPhrase(original, it) }
    }

    /**
     * Folded phrases of [rawQuery]: each quoted segment is one multi-token
     * phrase (adjacency required); each unquoted token a singleton phrase.
     */
    fun phrasesOf(rawQuery: String): List<List<String>> {
        val phrases = mutableListOf<List<String>>()
        for (segment in QueryQuotes.segments(rawQuery)) {
            val tokens = scanTokens(segment.text)
            if (tokens.isEmpty()) continue
            if (segment.quoted) phrases.add(tokens)
            else tokens.forEach { phrases.add(listOf(it)) }
        }
        return phrases.distinct()
    }

    /** Folded tokens of [rawQuery], tokenized exactly as text is scanned.
     *  Flat: quotes act as plain separators here. */
    fun tokensOf(rawQuery: String): List<String> = scanTokens(rawQuery).distinct()

    /**
     * Filename-lane phrase test: whether [tokens] appear adjacently in the
     * already-normalized, lowercased [target]. The first token may start
     * mid-word (the filename lane's contains semantics, SEARCH-QRY-004); each
     * later token must start the immediately following word. Both sides are
     * TextNormalizer output, so covered punctuation is already gone.
     */
    // @spec SEARCH-QRY-011
    fun containsAdjacent(target: String, tokens: List<String>): Boolean {
        if (tokens.isEmpty()) return false
        if (tokens.size == 1) return target.contains(tokens[0])
        val pattern = tokens.joinToString(separator = """\S*\s+""") { Regex.escape(it) }
        return Regex(pattern).containsMatchIn(target)
    }

    /** Tokens of [s] in order, duplicates preserved (a quoted phrase may
     *  legitimately repeat a word). */
    private fun scanTokens(s: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < s.length) {
            val len = spanLengthAt(s, i)
            when (kindAt(s, i)) {
                Kind.TOKEN -> current.append(fold(s[i]))
                Kind.SEPARATOR -> if (current.isNotEmpty()) {
                    tokens.add(current.toString()); current.clear()
                }
                Kind.TRANSPARENT -> Unit
            }
            i += len
        }
        if (current.isNotEmpty()) tokens.add(current.toString())
        return tokens
    }

    /**
     * Matches the tokens of [phrase] as consecutive text tokens starting at
     * [start] (which must be a token start), each token-start anchored and
     * prefix extended — mirroring an FTS4 phrase of prefix terms. Whitespace
     * and deleted punctuation between the words are tolerated; an intervening
     * word is not, and neither is a merged token ("invoice-4471" normalizes
     * to ONE indexed token, so the phrase "invoice 4471" does not match it —
     * exactly as FTS scores it). Returns the inclusive end index of the last
     * token's match, or null.
     */
    private fun matchPhraseAt(original: String, start: Int, phrase: List<String>): Int? {
        var i = start
        var end = -1
        for ((index, token) in phrase.withIndex()) {
            end = matchAt(original, i, token) ?: return null
            if (index == phrase.lastIndex) break
            // Advance to the start of the NEXT text token: token characters
            // seen before any separator continue the current normalized token
            // (a prefix match consumes only part of its word).
            i = end + 1
            var seenSeparator = false
            while (i < original.length) {
                val kind = kindAt(original, i)
                if (kind == Kind.TOKEN && seenSeparator) break
                if (kind == Kind.SEPARATOR) seenSeparator = true
                i += spanLengthAt(original, i)
            }
            if (i >= original.length) return null
        }
        return end
    }

    /** First-occurrence-only scan of [phrase] over [original]. */
    private fun hasPhrase(original: String, phrase: List<String>): Boolean {
        var i = 0
        var prevWasTokenChar = false
        while (i < original.length) {
            val kind = kindAt(original, i)
            if (kind == Kind.TOKEN && !prevWasTokenChar &&
                matchPhraseAt(original, i, phrase) != null
            ) {
                return true
            }
            if (kind != Kind.TRANSPARENT) prevWasTokenChar = kind == Kind.TOKEN
            i += spanLengthAt(original, i)
        }
        return false
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

    /**
     * unicode61(remove_diacritics=1)-equivalent fold of a whole string — the
     * identity under which the FTS index treats two terms as the same. Query
     * construction dedupes with this key so "café cafe" cannot emit one index
     * term twice (doubling its matchinfo counts).
     */
    fun indexFold(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(fold(c))
        return sb.toString()
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
