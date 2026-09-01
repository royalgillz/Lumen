package com.lumen.app.domain.model

import com.lumen.app.data.db.entity.DocumentEntity
import com.lumen.app.data.db.entity.ExternalOpenEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentDocumentsTest {

    private fun libDoc(filename: String, openedAt: Long) = DocumentEntity(
        uri = "content://lib/$filename",
        filename = filename,
        lastOpenedAt = openedAt,
    )

    private fun extOpen(
        name: String,
        openedAt: Long,
        persisted: Boolean = false,
        accessLost: Boolean = false,
    ) = ExternalOpenEntity(
        docUri = "content://ext/$name",
        displayName = name,
        lastOpenedAt = openedAt,
        persisted = persisted,
        accessLost = accessLost,
    )

    // @spec SEARCH-UI-004
    @Test
    fun merge_interleavesBothSourcesByLastOpenedDescending() {
        val merged = mergeRecents(
            library = listOf(libDoc("lib-old.pdf", 100), libDoc("lib-new.pdf", 400)),
            external = listOf(extOpen("ext-mid.pdf", 300), extOpen("ext-older.pdf", 200)),
        )

        assertEquals(listOf(400L, 300L, 200L, 100L), merged.map { it.lastOpenedAt })
        assertTrue(merged[0] is RecentDocument.Library)
        assertTrue(merged[1] is RecentDocument.External)
    }

    // @spec SEARCH-UI-004
    @Test
    fun merge_limitsToEightOverall_notPerSource() {
        val merged = mergeRecents(
            library = (1..6).map { libDoc("lib$it.pdf", it * 10L) },
            external = (1..6).map { extOpen("ext$it.pdf", it * 10L + 5) },
        )

        assertEquals(8, merged.size)
        // The 8 newest of the 12 combined, still strictly descending.
        assertEquals(merged.map { it.lastOpenedAt }.sortedDescending(), merged.map { it.lastOpenedAt })
        assertEquals(65L, merged.first().lastOpenedAt)
        assertEquals(30L, merged.last().lastOpenedAt)
    }

    // @spec SEARCH-UI-004
    @Test
    fun merge_worksWithOneSourceEmpty() {
        val onlyExternal = mergeRecents(library = emptyList(), external = listOf(extOpen("a.pdf", 1)))
        assertEquals(1, onlyExternal.size)
        assertTrue(onlyExternal[0] is RecentDocument.External)

        val onlyLibrary = mergeRecents(library = listOf(libDoc("b.pdf", 1)), external = emptyList())
        assertEquals(1, onlyLibrary.size)
        assertTrue(onlyLibrary[0] is RecentDocument.Library)

        assertTrue(mergeRecents(emptyList(), emptyList()).isEmpty())
    }

    // Deterministic tie-break: on equal timestamps the library document leads —
    // it is the entry guaranteed openable (SAF grant held via its folder).
    // @spec SEARCH-UI-004
    @Test
    fun merge_tiesResolveLibraryFirst() {
        val merged = mergeRecents(
            library = listOf(libDoc("lib.pdf", 100)),
            external = listOf(extOpen("ext.pdf", 100)),
        )
        assertTrue(merged[0] is RecentDocument.Library)
        assertTrue(merged[1] is RecentDocument.External)
    }

    // @spec SEARCH-UI-007
    @Test
    fun externalEntries_carryStoredDisplayNameAndUri() {
        val merged = mergeRecents(
            library = emptyList(),
            external = listOf(extOpen("Visa Appointment.pdf", 1)),
        )

        val row = merged.single() as RecentDocument.External
        assertEquals("Visa Appointment.pdf", row.displayName)
        assertEquals("content://ext/Visa Appointment.pdf", row.docUri)
    }

    // Expired rows are shown honestly but never above a live row — recency
    // ordering is a promise about what the user can reopen.
    // @spec LIB-EXT-002
    @Test
    fun merge_expiredExternalRowsSortAfterEveryLiveRow() {
        val merged = mergeRecents(
            library = listOf(libDoc("lib-old.pdf", 10)),
            external = listOf(
                extOpen("expired-newest.pdf", 500, accessLost = true),
                extOpen("live-ext.pdf", 100),
            ),
        )

        assertEquals(
            listOf("content://ext/live-ext.pdf", "content://lib/lib-old.pdf", "content://ext/expired-newest.pdf"),
            merged.map { if (it is RecentDocument.External) it.docUri else (it as RecentDocument.Library).document.uri },
        )
        assertTrue(merged.last().expired)
    }

    // Under the cap, live rows win eviction: a dead entry must not push a live
    // one below the fold.
    // @spec LIB-EXT-002
    @Test
    fun merge_underTheCap_expiredRowsAreEvictedBeforeLiveOnes() {
        val merged = mergeRecents(
            library = (1..8).map { libDoc("lib$it.pdf", it * 10L) },
            external = listOf(extOpen("expired.pdf", 999, accessLost = true)),
        )

        assertEquals(8, merged.size)
        assertTrue(merged.none { it.expired })
    }

    // Within the expired band, ordering stays recency-descending.
    // @spec LIB-EXT-002
    @Test
    fun merge_expiredBandKeepsRecencyOrderWithinItself() {
        val merged = mergeRecents(
            library = emptyList(),
            external = listOf(
                extOpen("expired-old.pdf", 10, accessLost = true),
                extOpen("expired-new.pdf", 20, accessLost = true),
                extOpen("live.pdf", 5),
            ),
        )

        assertEquals(
            listOf("live.pdf", "expired-new.pdf", "expired-old.pdf"),
            merged.map { (it as RecentDocument.External).displayName },
        )
    }

    // The UI renders expired state and keep-access affordances from these flags.
    // @spec LIB-EXT-001, LIB-EXT-002
    @Test
    fun externalEntries_threadPersistedAndAccessLostThrough() {
        val merged = mergeRecents(
            library = emptyList(),
            external = listOf(
                extOpen("held.pdf", 2, persisted = true),
                extOpen("dead.pdf", 1, accessLost = true),
            ),
        )

        val held = merged[0] as RecentDocument.External
        assertTrue(held.persisted)
        assertTrue(!held.accessLost)
        assertTrue(!held.expired)

        val dead = merged[1] as RecentDocument.External
        assertTrue(!dead.persisted)
        assertTrue(dead.accessLost)
        assertTrue(dead.expired)
    }

    // Library rows are never expired — their grant is held via the folder.
    // @spec LIB-EXT-002
    @Test
    fun libraryRows_areNeverExpired() {
        val merged = mergeRecents(library = listOf(libDoc("lib.pdf", 1)), external = emptyList())
        assertTrue(!merged.single().expired)
    }

    // Library rows with a null lastOpenedAt cannot occur (the recents query
    // filters them), but the merge must not crash if handed one — it sorts last.
    @Test
    fun merge_treatsNullLibraryTimestampAsOldest() {
        val merged = mergeRecents(
            library = listOf(libDoc("opened.pdf", 50), libDoc("never.pdf", 0).copy(lastOpenedAt = null)),
            external = listOf(extOpen("ext.pdf", 25)),
        )
        assertEquals("content://lib/never.pdf", (merged.last() as RecentDocument.Library).document.uri)
    }
}
