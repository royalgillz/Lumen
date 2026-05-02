package com.lumen.app.data.db

object FtsQuerySanitizer {
    fun sanitize(input: String): String {
        val cleaned = input.replace('"', ' ').replace('*', ' ').trim()
        return "\"$cleaned\""
    }
}
