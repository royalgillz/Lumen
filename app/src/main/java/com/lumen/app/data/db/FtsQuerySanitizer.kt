package com.lumen.app.data.db

object FtsQuerySanitizer {
    /**
     * Turns raw user input into a safe FTS4 MATCH expression.
     * Each token becomes "token*" joined with AND, so multi-word queries
     * match lines containing ALL terms (in any order) with prefix support.
     * e.g. "climate change" → "climate* AND change*"
     * Returns null if the input produces no usable tokens (caller should skip the query).
     */
    fun sanitize(input: String): String? {
        val tokens = input
            .replace(Regex("""["*()\-^:]"""), " ")
            .trim()
            .split("\\s+".toRegex())
            .filter { it.isNotEmpty() }
        return if (tokens.isEmpty()) null else tokens.joinToString(" AND ") { "$it*" }
    }
}
