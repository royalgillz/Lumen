package com.lumen.app.domain.model

data class SearchResult(
    val lineId: Long,
    val docId: Long,
    val uri: String,
    val filename: String,
    // 0-indexed internally; display as pageNumber + 1
    val pageNumber: Int,
    val lineNumber: Int,
    val snippet: String,
    val isOcr: Boolean,
    val folderName: String,
    val isFilenameMatch: Boolean = false,
    // 0-based rank of this match among matches on the same page, in reading order.
    // Lets the viewer open with the correct occurrence highlighted, not the first.
    val occurrenceOnPage: Int = 0,
)
