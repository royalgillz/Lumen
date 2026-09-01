package com.lumen.app.data.fs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reading-position entries are "encodedUri:page" strings in one DataStore set
 * (legacy builds used "uriHashCode:page"). A file rename rewrites the renamed
 * document's entry to its new key and drops every old-key form.
 */
class LastPageRewriteTest {

    // @spec LIB-REN-002
    @Test
    fun rewrite_movesPageToNewKey_removingOldEntries() {
        val entries = setOf("encOld:17", "encOther:3")

        val rewritten = rewriteLastPageEntries(
            entries = entries,
            oldKeys = setOf("encOld", "12345"),
            newKey = "encNew",
        )

        assertEquals(setOf("encNew:17", "encOther:3"), rewritten)
    }

    // @spec LIB-REN-002
    @Test
    fun rewrite_dropsLegacyHashEntryToo() {
        val entries = setOf("encOld:17", "12345:9", "encOther:3")

        val rewritten = rewriteLastPageEntries(
            entries = entries,
            oldKeys = setOf("encOld", "12345"),
            newKey = "encNew",
        )

        // The encoded entry's page wins; both old forms are gone.
        assertEquals(setOf("encNew:17", "encOther:3"), rewritten)
    }

    // @spec LIB-REN-002
    @Test
    fun rewrite_isNoOpWhenDocumentHasNoSavedPosition() {
        val entries = setOf("encOther:3")
        assertEquals(entries, rewriteLastPageEntries(entries, setOf("encOld", "999"), "encNew"))
    }

    @Test
    fun rewrite_legacyOnlyEntryStillMigrates() {
        val entries = setOf("12345:9")
        assertEquals(
            setOf("encNew:9"),
            rewriteLastPageEntries(entries, setOf("encOld", "12345"), "encNew"),
        )
    }

    // A dead file that previously had the new path left an entry under the new
    // key — the renamed document's own position replaces it, never both.
    // @spec LIB-REN-014
    @Test
    fun rewrite_replacesGhostEntryUnderNewKey() {
        val entries = setOf("encOld:17", "encNew:42", "encOther:3")

        val rewritten = rewriteLastPageEntries(
            entries = entries,
            oldKeys = setOf("encOld", "12345"),
            newKey = "encNew",
        )

        assertEquals(setOf("encNew:17", "encOther:3"), rewritten)
    }

    // No saved position on the renamed document: the ghost must still be
    // purged, not silently inherited as the only entry under the new key.
    // @spec LIB-REN-014
    @Test
    fun rewrite_purgesGhostEvenWithoutAnOldPosition() {
        val entries = setOf("encNew:42", "encOther:3")

        val rewritten = rewriteLastPageEntries(
            entries = entries,
            oldKeys = setOf("encOld", "12345"),
            newKey = "encNew",
        )

        assertEquals(setOf("encOther:3"), rewritten)
    }

    // A ghost can also sit under the new URI's LEGACY hash key — getLastPage
    // falls back to it exactly when no encoded entry exists, so it must be
    // purged alongside the encoded form.
    // @spec LIB-REN-014
    @Test
    fun rewrite_purgesLegacyHashGhostUnderNewKey() {
        val entries = setOf("67890:42", "encOther:3")

        val rewritten = rewriteLastPageEntries(
            entries = entries,
            oldKeys = setOf("encOld", "12345"),
            newKey = "encNew",
            ghostKeys = setOf("encNew", "67890"),
        )

        assertEquals(setOf("encOther:3"), rewritten)
    }

    // @spec LIB-REN-014
    @Test
    fun rewrite_purgesBothGhostForms_whileMigratingThePosition() {
        val entries = setOf("encOld:17", "encNew:42", "67890:9", "encOther:3")

        val rewritten = rewriteLastPageEntries(
            entries = entries,
            oldKeys = setOf("encOld", "12345"),
            newKey = "encNew",
            ghostKeys = setOf("encNew", "67890"),
        )

        assertEquals(setOf("encNew:17", "encOther:3"), rewritten)
    }

    // Keep-access merge: the new URI's entries belong to THIS document, not a
    // dead file — with no old position, they are kept, never purged as ghosts.
    // @spec LIB-EXT-017
    @Test
    fun merge_keepsNewUriPosition_whenOldHasNone() {
        val entries = setOf("encNew:42", "encOther:3")

        val rewritten = rewriteLastPageEntries(
            entries = entries,
            oldKeys = setOf("encOld", "12345"),
            newKey = "encNew",
            ghostKeys = setOf("encNew", "67890"),
            keepNewWhenOldMissing = true,
        )

        assertEquals(setOf("encNew:42", "encOther:3"), rewritten)
    }

    // When both URIs carry a position, the old one wins — it is the session
    // the user just read; the new URI's stale entry goes.
    // @spec LIB-EXT-017
    @Test
    fun merge_oldPositionWins_whenBothExist() {
        val entries = setOf("encOld:17", "encNew:42", "encOther:3")

        val rewritten = rewriteLastPageEntries(
            entries = entries,
            oldKeys = setOf("encOld", "12345"),
            newKey = "encNew",
            ghostKeys = setOf("encNew", "67890"),
            keepNewWhenOldMissing = true,
        )

        assertEquals(setOf("encNew:17", "encOther:3"), rewritten)
    }
}
