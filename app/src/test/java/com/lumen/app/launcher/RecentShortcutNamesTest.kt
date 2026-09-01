package com.lumen.app.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentShortcutNamesTest {

    private val uri =
        "content://com.android.externalstorage.documents/tree/primary%3ADocs/document/primary%3ADocs%2FVisa%20I-20.pdf"

    // @spec SEARCH-ENTRY-006
    @Test
    fun shortcutId_isStableForSameUri() {
        assertEquals(RecentShortcutNames.shortcutIdFor(uri), RecentShortcutNames.shortcutIdFor(uri))
    }

    // @spec SEARCH-ENTRY-006
    @Test
    fun shortcutId_differsAcrossUris() {
        assertNotEquals(
            RecentShortcutNames.shortcutIdFor("content://a/doc/1.pdf"),
            RecentShortcutNames.shortcutIdFor("content://a/doc/2.pdf"),
        )
    }

    // Ids surface in launcher data and dumpsys — no fragment of the URI or
    // filename may appear in them.
    // @spec SEARCH-ENTRY-006
    @Test
    fun shortcutId_containsNoUriContent() {
        val id = RecentShortcutNames.shortcutIdFor(uri)
        assertTrue(id.startsWith("recent-"))
        assertEquals("recent-".length + 16, id.length)
        assertFalse(id.contains("Visa", ignoreCase = true))
        assertFalse(id.contains("Docs", ignoreCase = true))
        assertFalse(id.contains("content", ignoreCase = true))
    }

    // @spec SEARCH-ENTRY-005
    @Test
    fun capLabel_keepsShortLabelsUntouched() {
        assertEquals("I-20 Fall 2025", RecentShortcutNames.capLabel("I-20 Fall 2025"))
    }

    // @spec SEARCH-ENTRY-005
    @Test
    fun capLabel_ellipsizesAtCap() {
        val long = "Employment Authorization Document Renewal"
        val capped = RecentShortcutNames.capLabel(long)
        assertEquals(RecentShortcutNames.SHORT_LABEL_MAX, capped.length)
        assertTrue(capped.endsWith("…"))
        assertTrue(long.startsWith(capped.dropLast(1)))
    }

    // ShortcutInfo rejects empty labels; blank input must never reach it.
    // @spec SEARCH-ENTRY-005
    @Test
    fun capLabel_blankFallsBackToPdf() {
        assertEquals("PDF", RecentShortcutNames.capLabel("   "))
        assertEquals("PDF", RecentShortcutNames.capLabel(""))
    }

    // @spec SEARCH-ENTRY-005
    @Test
    fun capLabel_respectsCustomMax() {
        val long = "x".repeat(100)
        assertEquals(
            RecentShortcutNames.LONG_LABEL_MAX,
            RecentShortcutNames.capLabel(long, RecentShortcutNames.LONG_LABEL_MAX).length,
        )
    }
}
