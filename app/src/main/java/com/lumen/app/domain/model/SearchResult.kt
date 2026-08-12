package com.lumen.app.domain.model

data class SearchResult(
    val lineId: Long,
    val docId: Long,
    val uri: String,
    val filename: String,
    // What the document is called on screen (customTitle > derivedTitle >
    // filename); filename stays for file operations and the caption.
    val displayTitle: String,
    // 0-indexed internally; display as pageNumber + 1
    val pageNumber: Int,
    val lineNumber: Int,
    // Plain snippet text — no markup; copy/share uses it verbatim.
    val snippet: String,
    // Match spans within [snippet]; bold/highlight styling is applied at render time.
    val snippetHighlights: List<IntRange> = emptyList(),
    val isOcr: Boolean,
    val folderName: String,
    val isFilenameMatch: Boolean = false,
    // 0-based rank of this match among matches on the same page, in reading order.
    // Lets the viewer open with the correct occurrence highlighted, not the first.
    val occurrenceOnPage: Int = 0,
    // Number of query-token hits on the page (0 for filename-only matches).
    val hitCount: Int = 0,
)
