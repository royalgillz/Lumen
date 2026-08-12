package com.lumen.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumen.app.data.db.entity.DocumentEntity
import com.lumen.app.data.db.entity.PageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentRecencyDaoTest {

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

    private suspend fun insertDoc(
        filename: String,
        status: String = "indexed",
        treeUri: String = "content://tree/A",
        lastOpenedAt: Long? = null,
    ): Long = db.documentDao().upsert(
        DocumentEntity(
            uri = "content://test/$filename",
            filename = filename,
            status = status,
            treeUri = treeUri,
            lastOpenedAt = lastOpenedAt,
        )
    )

    // @spec SEARCH-UI-004
    @Test
    fun recentlyOpened_orderedDescending_statusBlind_neverOpenedExcluded() = runTest {
        insertDoc("never.pdf", lastOpenedAt = null)
        insertDoc("encrypted.pdf", status = "encrypted", lastOpenedAt = 300)
        insertDoc("older.pdf", lastOpenedAt = 100)
        insertDoc("newer.pdf", lastOpenedAt = 200)

        val recent = db.documentDao().observeRecentlyOpened(8).first()

        // Status-blind (the encrypted doc leads) and never-opened rows absent.
        assertEquals(
            listOf("encrypted.pdf", "newer.pdf", "older.pdf"),
            recent.map { it.filename },
        )
    }

    // @spec SEARCH-UI-004
    @Test
    fun recentlyOpened_respectsLimit() = runTest {
        repeat(10) { i -> insertDoc("doc$i.pdf", lastOpenedAt = i.toLong() + 1) }
        assertEquals(8, db.documentDao().observeRecentlyOpened(8).first().size)
    }

    // @spec LIB-REC-001
    @Test
    fun markOpened_setsTimestamp_andNoOpsForUnknownUri() = runTest {
        insertDoc("known.pdf")

        db.documentDao().markOpened("content://test/known.pdf", 42L)
        db.documentDao().markOpened("content://external/unknown.pdf", 99L)

        val docs = db.documentDao().observeAll().first()
        assertEquals(1, docs.size)
        assertEquals(42L, docs[0].lastOpenedAt)
    }

    // @spec LIB-HLTH-003
    @Test
    fun folderStats_aggregatesFilesPagesAndOcrPerTreeUri() = runTest {
        val a1 = insertDoc("a1.pdf", treeUri = "content://tree/A")
        val a2 = insertDoc("a2.pdf", treeUri = "content://tree/A")
        val b1 = insertDoc("b1.pdf", treeUri = "content://tree/B")

        db.pageDao().insert(PageEntity(docId = a1, pageNumber = 0, isOcr = false))
        db.pageDao().insert(PageEntity(docId = a1, pageNumber = 1, isOcr = true))
        db.pageDao().insert(PageEntity(docId = a2, pageNumber = 0, isOcr = true))
        // b1 has zero pages — LEFT JOIN must still count the file.

        val stats = db.documentDao().observeFolderStats().first().associateBy { it.treeUri }

        val a = stats.getValue("content://tree/A")
        assertEquals(2, a.files)
        assertEquals(3, a.pages)
        assertEquals(2, a.ocrPages)

        val b = stats.getValue("content://tree/B")
        assertEquals(1, b.files)
        assertEquals(0, b.pages)
        assertEquals(0, b.ocrPages)
    }
}
