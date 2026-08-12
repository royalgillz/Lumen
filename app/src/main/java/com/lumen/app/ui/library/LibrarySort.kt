package com.lumen.app.ui.library

import com.lumen.app.data.db.entity.DocumentEntity
import java.text.Collator

/** Library list orderings. RECENTLY_ADDED is the default and matches the
 *  pre-sort-menu behavior; the choice persists in DataStore. */
enum class LibrarySortOrder {
    RECENTLY_ADDED,
    RECENTLY_INDEXED,
    RECENTLY_OPENED,
    NAME,
}

/** Menu order and display labels for the Library's sort dropdown. */
val sortOptionLabels = listOf(
    LibrarySortOrder.RECENTLY_ADDED to "Recently added",
    LibrarySortOrder.RECENTLY_INDEXED to "Recently indexed",
    LibrarySortOrder.RECENTLY_OPENED to "Recently opened",
    LibrarySortOrder.NAME to "Name",
)

/**
 * Sorts the Library's document list. Recency sorts place documents without the
 * relevant timestamp last, ordered among themselves by Recently added; NAME
 * compares case-insensitively and locale-aware.
 */
// @spec LIB-SORT-001, LIB-SORT-003, LIB-SORT-004
fun sortLibrary(docs: List<DocumentEntity>, order: LibrarySortOrder): List<DocumentEntity> =
    when (order) {
        LibrarySortOrder.RECENTLY_ADDED -> docs.sortedByDescending { it.addedAt }
        LibrarySortOrder.RECENTLY_INDEXED -> docs.sortedWith(
            compareByDescending<DocumentEntity> { it.indexedAt != null }
                .thenByDescending { it.indexedAt ?: 0L }
                .thenByDescending { it.addedAt }
        )
        LibrarySortOrder.RECENTLY_OPENED -> docs.sortedWith(
            compareByDescending<DocumentEntity> { it.lastOpenedAt != null }
                .thenByDescending { it.lastOpenedAt ?: 0L }
                .thenByDescending { it.addedAt }
        )
        LibrarySortOrder.NAME -> {
            // PRIMARY strength: case-insensitive and locale-aware in one step.
            val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
            docs.sortedWith(compareBy(collator) { it.filename })
        }
    }
