package com.lumen.app.data.db.entity

import org.junit.Assert.assertEquals
import org.junit.Test

class MergeForReindexTest {

    private val existing = DocumentEntity(
        id = 7,
        uri = "content://test/a.pdf",
        filename = "a.pdf",
        treeUri = "content://tree/t",
        status = DocumentEntity.STATUS_INDEXED,
        pageCount = 3,
        addedAt = 1_000L,
        indexedAt = 2_000L,
        lastOpenedAt = 3_000L,
        derivedTitle = "Old Title",
    )

    private val fresh = DocumentEntity(
        uri = "content://test/a.pdf",
        filename = "a.pdf",
        treeUri = "content://tree/t",
        status = DocumentEntity.STATUS_INDEXED,
        pageCount = 5,
        addedAt = 9_999L,
        indexedAt = 8_000L,
        derivedTitle = "New Title",
    )

    // @spec LIB-TTL-009
    @Test
    fun preservesIdentityAndUserRecency() {
        val merged = mergeForReindex(existing, fresh)
        assertEquals(7L, merged.id)
        assertEquals(1_000L, merged.addedAt)
        // The regression this spec exists for: re-indexing must not wipe recency.
        assertEquals(3_000L, merged.lastOpenedAt)
    }

    // @spec LIB-TTL-009
    @Test
    fun takesFreshExtractionColumns() {
        val merged = mergeForReindex(existing, fresh)
        assertEquals(5, merged.pageCount)
        assertEquals(8_000L, merged.indexedAt)
        assertEquals("New Title", merged.derivedTitle)
    }

    // @spec LIB-TTL-009
    @Test
    fun noExistingRow_freshWinsUnchanged() {
        assertEquals(fresh, mergeForReindex(null, fresh))
    }
}
