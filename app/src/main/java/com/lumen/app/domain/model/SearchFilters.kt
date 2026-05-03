package com.lumen.app.domain.model

data class SearchFilters(
    // Stable folder identifiers (tree document IDs), not raw URI strings.
    val folderIds: Set<String> = emptySet(),
    val ocrOnly: Boolean = false,
    val sortOrder: SortOrder = SortOrder.RELEVANCE,
)

enum class SortOrder {
    RELEVANCE, FILENAME, MOST_RECENT;

    val displayName: String get() = when (this) {
        RELEVANCE -> "Relevance"
        FILENAME -> "Filename"
        MOST_RECENT -> "Most recent"
    }
}
