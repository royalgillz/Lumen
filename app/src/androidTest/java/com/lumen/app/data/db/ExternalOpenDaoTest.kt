package com.lumen.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumen.app.data.db.entity.ExternalOpenEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalOpenDaoTest {

    private lateinit var db: LumenDatabase

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, LumenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun open(name: String, openedAt: Long, persisted: Boolean = false) = ExternalOpenEntity(
        docUri = "content://ext/$name",
        displayName = name,
        lastOpenedAt = openedAt,
        persisted = persisted,
    )

    private suspend fun row(name: String): ExternalOpenEntity =
        db.externalOpenDao().observeRecent(8).first().single { it.displayName == name }

    // @spec LIB-REC-001
    @Test
    fun recordOpen_insertsAndReopenUpdatesInPlace() = runTest {
        db.externalOpenDao().recordOpen(open("a.pdf", 100))
        db.externalOpenDao().recordOpen(open("a.pdf", 200))

        val rows = db.externalOpenDao().observeRecent(8).first()
        assertEquals(1, rows.size)
        assertEquals(200L, rows[0].lastOpenedAt)
    }

    // @spec LIB-REC-001
    @Test
    fun recordOpen_prunesToEightNewest_returningPrunedUris() = runTest {
        repeat(8) { i -> db.externalOpenDao().recordOpen(open("doc$i.pdf", i + 1L)) }

        // The 9th open must evict the oldest (doc0, openedAt=1) and report it,
        // so the caller can release its persisted grant (LIB-REC-007).
        val pruned = db.externalOpenDao().recordOpen(open("doc9.pdf", 100))

        assertEquals(listOf("content://ext/doc0.pdf"), pruned)
        val rows = db.externalOpenDao().observeRecent(8).first()
        assertEquals(8, rows.size)
        assertEquals("doc9.pdf", rows[0].displayName)
        assertTrue(rows.none { it.displayName == "doc0.pdf" })
    }

    // Eviction is expired-first: the cap must never evict a live row (whose
    // kept-access grant would be released) while a newer expired row survives.
    // @spec LIB-EXT-002
    @Test
    fun recordOpen_prunesExpiredRowsBeforeAnyLiveRow() = runTest {
        repeat(8) { i -> db.externalOpenDao().recordOpen(open("doc$i.pdf", i + 1L, persisted = true)) }
        // The NEWEST row goes expired; the oldest live rows stay older than it.
        db.externalOpenDao().markAccessLost("content://ext/doc7.pdf")

        val pruned = db.externalOpenDao().recordOpen(open("doc9.pdf", 100))

        // The expired doc7 is evicted, not the older-but-live doc0.
        assertEquals(listOf("content://ext/doc7.pdf"), pruned)
        val rows = db.externalOpenDao().observeRecent(8).first()
        assertTrue(rows.any { it.displayName == "doc0.pdf" })
        assertTrue(rows.none { it.displayName == "doc7.pdf" })
    }

    // A clock that moved backwards must never make the document being opened
    // the "oldest" row: recordOpen clamps the stored timestamp past the newest
    // existing row, so the prune evicts a genuinely old entry instead.
    // @spec LIB-REC-008
    @Test
    fun recordOpen_backwardsClock_neverPrunesTheRowBeingOpened() = runTest {
        repeat(8) { i -> db.externalOpenDao().recordOpen(open("doc$i.pdf", 1000L + i)) }

        val pruned = db.externalOpenDao().recordOpen(open("new.pdf", 5))

        assertEquals(listOf("content://ext/doc0.pdf"), pruned)
        val rows = db.externalOpenDao().observeRecent(8).first()
        assertEquals("new.pdf", rows[0].displayName)
        assertTrue(rows.none { it.displayName == "new.pdf" && it.lastOpenedAt <= 1007L })
    }

    // @spec LIB-REC-008
    @Test
    fun recordOpen_backwardsClock_reopenStillSortsNewest() = runTest {
        db.externalOpenDao().recordOpen(open("a.pdf", 100))
        db.externalOpenDao().recordOpen(open("b.pdf", 200))

        db.externalOpenDao().recordOpen(open("a.pdf", 50))

        assertEquals(
            listOf("a.pdf", "b.pdf"),
            db.externalOpenDao().observeRecent(8).first().map { it.displayName },
        )
    }

    // @spec SEARCH-UI-004
    @Test
    fun observeRecent_ordersByLastOpenedDescending() = runTest {
        db.externalOpenDao().recordOpen(open("older.pdf", 10))
        db.externalOpenDao().recordOpen(open("newest.pdf", 30))
        db.externalOpenDao().recordOpen(open("mid.pdf", 20))

        assertEquals(
            listOf("newest.pdf", "mid.pdf", "older.pdf"),
            db.externalOpenDao().observeRecent(8).first().map { it.displayName },
        )
    }

    // Self-heal after a lost-access open failure removes exactly that row.
    // @spec LIB-REC-005
    @Test
    fun delete_removesOnlyTheGivenUri() = runTest {
        db.externalOpenDao().recordOpen(open("dead.pdf", 10))
        db.externalOpenDao().recordOpen(open("alive.pdf", 20))

        db.externalOpenDao().delete("content://ext/dead.pdf")

        assertEquals(
            listOf("alive.pdf"),
            db.externalOpenDao().observeRecent(8).first().map { it.displayName },
        )
    }

    // Grant persistence is recorded per open, from the caller's record-time check.
    // @spec LIB-EXT-001
    @Test
    fun recordOpen_storesTheCallersPersistedFlag() = runTest {
        db.externalOpenDao().recordOpen(open("held.pdf", 10, persisted = true))
        db.externalOpenDao().recordOpen(open("transient.pdf", 20, persisted = false))

        assertTrue(row("held.pdf").persisted)
        assertTrue(!row("transient.pdf").persisted)
    }

    // A dead non-persisted grant marks the row expired instead of deleting it.
    // @spec LIB-EXT-002
    @Test
    fun markAccessLost_flagsOnlyTheGivenRow_whichStaysListed() = runTest {
        db.externalOpenDao().recordOpen(open("dead.pdf", 10))
        db.externalOpenDao().recordOpen(open("alive.pdf", 20))

        db.externalOpenDao().markAccessLost("content://ext/dead.pdf")

        assertTrue(row("dead.pdf").accessLost)
        assertTrue(!row("alive.pdf").accessLost)
        assertEquals(2, db.externalOpenDao().observeRecent(8).first().size)
    }

    // A successful open is the proof access works again.
    // @spec LIB-EXT-002
    @Test
    fun recordOpen_clearsAccessLost() = runTest {
        db.externalOpenDao().recordOpen(open("a.pdf", 10))
        db.externalOpenDao().markAccessLost("content://ext/a.pdf")

        db.externalOpenDao().recordOpen(open("a.pdf", 20))

        assertTrue(!row("a.pdf").accessLost)
    }

    // Keep-access succeeded in place: persisted set, stale expired mark cleared.
    // @spec LIB-EXT-001
    @Test
    fun markPersisted_setsPersistedAndClearsAccessLost() = runTest {
        db.externalOpenDao().recordOpen(open("a.pdf", 10))
        db.externalOpenDao().markAccessLost("content://ext/a.pdf")

        db.externalOpenDao().markPersisted("content://ext/a.pdf")

        assertTrue(row("a.pdf").persisted)
        assertTrue(!row("a.pdf").accessLost)
    }

    // Offers appear once, never nag: dismissal survives the reopen's REPLACE upsert.
    // @spec LIB-EXT-005
    @Test
    fun offerDismissed_survivesReopen() = runTest {
        db.externalOpenDao().recordOpen(open("a.pdf", 10))
        db.externalOpenDao().setOfferDismissed("content://ext/a.pdf")

        db.externalOpenDao().recordOpen(open("a.pdf", 20))

        assertTrue(row("a.pdf").offerDismissed)
        assertEquals(20L, row("a.pdf").lastOpenedAt)
    }

    // The access-loss split reads the row to choose delete vs markAccessLost.
    // @spec LIB-EXT-001, LIB-EXT-002
    @Test
    fun getByUri_returnsTheRowWithItsFlags_orNull() = runTest {
        db.externalOpenDao().recordOpen(open("a.pdf", 10, persisted = true))
        db.externalOpenDao().setOfferDismissed("content://ext/a.pdf")

        val found = db.externalOpenDao().getByUri("content://ext/a.pdf")!!
        assertTrue(found.persisted)
        assertTrue(found.offerDismissed)
        assertEquals(null, db.externalOpenDao().getByUri("content://ext/missing.pdf"))
    }

    // Delete search index wipes external recents too; getAllUris feeds the
    // grant release that precedes the wipe.
    // @spec LIB-REC-006
    @Test
    fun deleteAll_clearsTable_andAllUrisEnumeratesBeforehand() = runTest {
        db.externalOpenDao().recordOpen(open("a.pdf", 1))
        db.externalOpenDao().recordOpen(open("b.pdf", 2))

        assertEquals(
            setOf("content://ext/a.pdf", "content://ext/b.pdf"),
            db.externalOpenDao().getAllUris().toSet(),
        )

        db.externalOpenDao().deleteAll()
        assertTrue(db.externalOpenDao().observeRecent(8).first().isEmpty())
    }
}
