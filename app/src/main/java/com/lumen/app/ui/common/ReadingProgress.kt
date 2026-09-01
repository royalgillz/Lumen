package com.lumen.app.ui.common

import android.net.Uri

/**
 * Saved reading position for [uri] from the bulk last-pages map
 * (`SafRepository.lastPages`) — raw-keyed by encoded URI, with the legacy
 * `uri.hashCode()` keys of older builds honored as a fallback, mirroring
 * `SafRepository.getLastPage`.
 */
// @spec LIB-PRG-002
fun lastPageFor(lastPages: Map<String, Int>, uri: String): Int? =
    lastPages[Uri.encode(uri)] ?: lastPages[uri.hashCode().toString()]

/**
 * "p. 34 of 120 · 28%" — [lastPage] is the 0-indexed saved page, displayed as
 * page + 1 and clamped to [pageCount] (a re-index can shrink a document past a
 * stale position, and the clamp also caps the percent at 100). Null when the
 * document has no usable page count, so pending or errored documents never
 * show progress.
 */
// @spec LIB-PRG-001
fun readingProgressLabel(lastPage: Int, pageCount: Int): String? {
    if (pageCount <= 0 || lastPage < 0) return null
    val shownPage = (lastPage + 1).coerceAtMost(pageCount)
    val percent = shownPage * 100 / pageCount
    return "p. $shownPage of $pageCount · $percent%"
}
