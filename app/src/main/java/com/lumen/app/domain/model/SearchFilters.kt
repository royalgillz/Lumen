package com.lumen.app.domain.model

data class SearchFilters(
    // Stable folder identifiers (tree document IDs), not raw URI strings.
    val folderIds: Set<String> = emptySet(),
    val ocrOnly: Boolean = false,
    val sortOrder: SortOrder = SortOrder.RELEVANCE,
    val indexedWithin: IndexedWithin = IndexedWithin.ANY_TIME,
)

enum class IndexedWithin {
    ANY_TIME, TODAY, THIS_WEEK, THIS_MONTH;

    val displayName: String get() = when (this) {
        ANY_TIME -> "Any time"
        TODAY -> "Today"
        THIS_WEEK -> "This week"
        THIS_MONTH -> "This month"
    }

    /** Earliest indexedAt (epoch millis) that passes this filter; 0 = no filtering. */
    fun cutoffMillis(now: Long = System.currentTimeMillis()): Long = when (this) {
        ANY_TIME -> 0L
        TODAY -> java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        THIS_WEEK -> now - 7L * 24 * 60 * 60 * 1000
        THIS_MONTH -> now - 30L * 24 * 60 * 60 * 1000
    }
}

enum class SortOrder {
    RELEVANCE, FILENAME, MOST_RECENT;

    val displayName: String get() = when (this) {
        RELEVANCE -> "Relevance"
        FILENAME -> "Filename"
        MOST_RECENT -> "Most recent"
    }
}
