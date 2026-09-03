package com.lumen.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumen.app.data.db.LumenDatabase
import com.lumen.app.data.db.entity.DocumentEntity
import com.lumen.app.data.db.entity.PageEntity
import com.lumen.app.data.db.entity.PageTextEntity
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The search body — ranking, merging, snippets — must run on the injected
 *  compute dispatcher for every caller, never on the caller's thread. */
@RunWith(AndroidJUnit4::class)
class SearchRepositoryDispatchTest {

    private lateinit var db: LumenDatabase

    /** Delegates to Default but records that it was actually used. */
    private class RecordingDispatcher : CoroutineDispatcher() {
        val used = AtomicBoolean(false)
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            used.set(true)
            Dispatchers.Default.dispatch(context, block)
        }
    }

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, LumenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    // @spec SEARCH-RANK-008
    @Test
    fun search_body_runs_on_the_injected_compute_dispatcher() = runBlocking {
        val docId = db.documentDao().upsert(
            DocumentEntity(
                uri = "content://doc/a.pdf",
                filename = "a.pdf",
                treeUri = "tree://a",
                status = DocumentEntity.STATUS_INDEXED,
                indexedAt = 100L,
            )
        )
        val pageId = db.pageDao().insert(PageEntity(docId = docId, pageNumber = 0))
        db.pageTextDao().insert(PageTextEntity(pageId = pageId, text = "alpha beta", textNorm = "alpha beta"))

        val dispatcher = RecordingDispatcher()
        val repo = SearchRepository(db.pageTextDao(), db.documentDao(), db.bookmarkDao(), dispatcher)

        val output = repo.search("alpha", "alpha*")

        assertTrue("search body must dispatch through the compute dispatcher", dispatcher.used.get())
        assertEquals(1, output.results.size)
        assertEquals(1, output.results.first().hitCount)
    }
}
