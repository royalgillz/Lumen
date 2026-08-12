package com.lumen.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumen.app.data.db.entity.DocumentEntity
import com.lumen.app.data.db.entity.DocumentTitleEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentTitleDaoTest {

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

    // @spec LIB-TTL-005
    @Test
    fun upsert_replace_delete() = runTest {
        db.documentTitleDao().upsert(DocumentTitleEntity("content://d/1", "First"))
        db.documentTitleDao().upsert(DocumentTitleEntity("content://d/1", "Second"))
        assertEquals("Second", db.documentTitleDao().getAll().single().title)

        db.documentTitleDao().delete("content://d/1")
        assertTrue(db.documentTitleDao().getAll().isEmpty())
    }

    // @spec LIB-TTL-005
    @Test
    fun titles_surviveDocumentRowDeletion() = runTest {
        db.documentDao().upsert(
            DocumentEntity(uri = "content://d/1", filename = "a.pdf", treeUri = "content://tree/t")
        )
        db.documentTitleDao().upsert(DocumentTitleEntity("content://d/1", "Keep Me"))

        // Vanished-file cleanup / index deletion removes document rows.
        db.documentDao().deleteAll()

        assertEquals("Keep Me", db.documentTitleDao().observeAll().first().single().title)
    }
}
