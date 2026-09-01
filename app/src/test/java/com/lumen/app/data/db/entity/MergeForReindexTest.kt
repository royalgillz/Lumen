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
        author = "Jane Doe",
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
    }

    // The merge runs before extraction: carrying the last-known title means a
    // failed, cancelled, or timed-out pass can't blank it (and can't invite
    // the page-0 backfill to stamp a worse guess); a successful pass
    // overwrites it post-extraction.
    // @spec LIB-TTL-012
    @Test
    fun carriesLastKnownDerivedTitleThroughTheUpsert() {
        assertEquals(existing.derivedTitle, mergeForReindex(existing, fresh).derivedTitle)
    }

    // @spec LIB-TTL-009
    @Test
    fun noExistingRow_freshWinsUnchanged() {
        assertEquals(fresh, mergeForReindex(null, fresh))
    }

    // The merge runs before extraction, so the fresh entity's null author must
    // not blank the last-known value; the post-extraction write owns it.
    // @spec LIB-TTL-012
    @Test
    fun carriesLastKnownAuthorThroughTheUpsert() {
        assertEquals("Jane Doe", mergeForReindex(existing, fresh).author)
    }

    // The TTL is ownership state managed only by setEphemeralExpiry /
    // the purge / the adoption-drop paths: a re-index pass must neither promote an
    // ephemeral doc (fresh null must not clear the TTL) nor demote a library
    // one.
    // @spec LIB-EXT-003
    @Test
    fun carriesEphemeralExpiryThroughTheUpsert() {
        val ephemeral = existing.copy(ephemeralExpiresAt = 7_777L)
        assertEquals(7_777L, mergeForReindex(ephemeral, fresh).ephemeralExpiresAt)
        // And a permanent doc stays permanent.
        assertEquals(null, mergeForReindex(existing, fresh).ephemeralExpiresAt)
    }
}
