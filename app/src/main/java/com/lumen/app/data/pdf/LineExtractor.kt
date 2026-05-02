package com.lumen.app.data.pdf

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LineExtractor @Inject constructor() {

    fun extract(pageText: String): List<String> {
        return pageText
            .split("\n")
            .map { it.trim() }
            .filter { it.length >= MIN_LINE_LENGTH }
    }

    companion object {
        private const val MIN_LINE_LENGTH = 3
    }
}
