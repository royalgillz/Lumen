package com.lumen.app.ui.library

import com.lumen.app.data.db.entity.DocumentEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySortTest {

    private fun doc(
        filename: String,
        addedAt: Long,
        indexedAt: Long? = null,
        lastOpenedAt: Long? = null,
    ) = DocumentEntity(
        uri = "content://test/$filename",
        filename = filename,
        addedAt = addedAt,
        indexedAt = indexedAt,
        lastOpenedAt = lastOpenedAt,
    )

    private fun names(docs: List<DocumentEntity>) = docs.map { it.filename }

    // @spec LIB-SORT-001
    @Test
    fun recentlyAdded_isAddedAtDescending() {
        val docs = listOf(doc("old.pdf", 1), doc("new.pdf", 3), doc("mid.pdf", 2))
        assertEquals(
            listOf("new.pdf", "mid.pdf", "old.pdf"),
            names(sortLibrary(docs, LibrarySortOrder.RECENTLY_ADDED)),
        )
    }

    // @spec LIB-SORT-001
    @Test
    fun recentlyIndexed_isIndexedAtDescending_neverIndexedLast() {
        val docs = listOf(
            doc("pending.pdf", addedAt = 9, indexedAt = null),
            doc("first.pdf", addedAt = 1, indexedAt = 100),
            doc("second.pdf", addedAt = 2, indexedAt = 200),
        )
        assertEquals(
            listOf("second.pdf", "first.pdf", "pending.pdf"),
            names(sortLibrary(docs, LibrarySortOrder.RECENTLY_INDEXED)),
        )
    }

    // @spec LIB-SORT-003
    @Test
    fun recentlyOpened_neverOpenedLast_orderedByAddedAmongThemselves() {
        val docs = listOf(
            doc("neverA.pdf", addedAt = 5),
            doc("openedOld.pdf", addedAt = 1, lastOpenedAt = 10),
            doc("neverB.pdf", addedAt = 7),
            doc("openedNew.pdf", addedAt = 2, lastOpenedAt = 20),
        )
        assertEquals(
            listOf("openedNew.pdf", "openedOld.pdf", "neverB.pdf", "neverA.pdf"),
            names(sortLibrary(docs, LibrarySortOrder.RECENTLY_OPENED)),
        )
    }

    // @spec LIB-SORT-004
    @Test
    fun name_isCaseInsensitive() {
        val docs = listOf(
            doc("banana.pdf", 1),
            doc("Apple.pdf", 2),
            doc("cherry.pdf", 3),
        )
        assertEquals(
            listOf("Apple.pdf", "banana.pdf", "cherry.pdf"),
            names(sortLibrary(docs, LibrarySortOrder.NAME)),
        )
    }
}
