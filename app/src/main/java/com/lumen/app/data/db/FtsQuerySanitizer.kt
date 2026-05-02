package com.lumen.app.data.db

object FtsQuerySanitizer {
    /**
     * Turns raw user input into a safe FTS4 MATCH expression.
     * Each token becomes "token*" joined with AND, so multi-word queries
     * match lines containing ALL terms (in any order) with prefix support.
     * e.g. "climate change" → "climate* AND change*"
     */
    fun sanitize(input: String): String {
        val tokens = input
            .replace('"', ' ')
            .replace('*', ' ')
            .trim()
            .split("\\s+".toRegex())
            .filter { it.isNotEmpty() }
        return tokens.joinToString(" AND ") { "$it*" }
    }
}
