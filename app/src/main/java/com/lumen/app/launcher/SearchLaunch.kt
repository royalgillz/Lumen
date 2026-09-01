package com.lumen.app.launcher

import android.content.Intent

/**
 * Recognizes the launcher-surface search entries (static shortcut, widget,
 * ACTION_PROCESS_TEXT) and extracts the query they carry. MainActivity routes
 * the result into PendingSearch; the start destination is a search surface in
 * both nav layouts, so no navigation is involved.
 */
object SearchLaunch {

    /** Fired by the static "Search" shortcut and the home-screen widget. */
    const val ACTION_OPEN_SEARCH = "com.lumen.app.action.OPEN_SEARCH"

    /** A search field, not a document: selections longer than this are noise. */
    const val QUERY_MAX_CHARS = 256

    /**
     * The query a launcher search entry carries, or null when [intent] is not
     * a search entry. Empty string means "open search, ready to type".
     */
    // @spec SEARCH-ENTRY-001, SEARCH-ENTRY-002
    fun searchQueryOf(intent: Intent?): String? = when (intent?.action) {
        ACTION_OPEN_SEARCH -> ""
        Intent.ACTION_PROCESS_TEXT -> capQuery(
            intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                ?: intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT_READONLY)
        )
        else -> null
    }

    /** Trimmed and capped; null (a sender that supplied no text) becomes the
     *  open-search request rather than a dropped launch. */
    // @spec SEARCH-ENTRY-002
    fun capQuery(raw: CharSequence?): String =
        raw?.toString()?.trim()?.take(QUERY_MAX_CHARS) ?: ""
}
