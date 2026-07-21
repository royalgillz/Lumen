package com.lumen.app.data.db

object FtsQuerySanitizer {
    /**
     * Turns raw user input into a safe FTS4 MATCH expression.
     * Each token becomes "token*" joined by a single space, so multi-word queries
     * match lines containing ALL terms (in any order) with prefix support.
     * e.g. "climate change" → "climate* change*"
     *
     * The join MUST be a space, not " AND ": whether SQLite is compiled with the
     * enhanced FTS4 query syntax is device-dependent, and under the standard
     * syntax "AND" is a LITERAL search term — it would match (and highlight) the
     * word "and" in documents. A bare space means implicit AND under BOTH syntaxes.
     *
     * Returns null if the input produces no usable tokens (caller should skip the query).
     */
    fun sanitize(input: String): String? {
        val tokens = input
            .replace(Regex("""["*()\-^:]"""), " ")
            .trim()
            .split("\\s+".toRegex())
            .filter { it.isNotEmpty() }
        return if (tokens.isEmpty()) null else tokens.joinToString(" ") { "$it*" }
    }
}
