package com.lumen.app.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration8To9Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LumenDatabase::class.java,
    )

    private val dbName = "migration-8-9-test"

    // @spec SEARCH-IDX-001, SEARCH-IDX-002
    @Test
    fun migrate8To9_backfillsTextNormAndRebuildsFts() {
        helper.createDatabase(dbName, 8).use { db ->
            db.execSQL(
                "INSERT INTO documents (uri, filename, treeUri, status, pageCount, lastModified, addedAt, sizeBytes) " +
                    "VALUES ('content://d/1', 'I-20.pdf', 'content://tree/t', 'indexed', 1, 0, 0, 0)"
            )
            db.execSQL("INSERT INTO pages (docId, pageNumber, isOcr, wordCount) VALUES (1, 0, 0, 0)")
            db.execSQL(
                "INSERT INTO page_text (pageId, text) VALUES (1, 'a valid F-1 visa and 802.11 rules')"
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 9, true, LumenDatabase.MIGRATION_8_9)

        // SEARCH-IDX-001: textNorm backfilled with the SQL replace chain's output.
        db.query("SELECT textNorm FROM page_text WHERE pageId = 1").use { c ->
            c.moveToFirst()
            assertEquals("a valid F1 visa and 80211 rules", c.getString(0))
        }

        // SEARCH-IDX-002: the FTS index matches the compact form of a punctuated
        // identifier — the whole point of the migration.
        db.query("SELECT count(*) FROM page_text_fts WHERE page_text_fts MATCH 'f1*'").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
        db.query("SELECT count(*) FROM page_text_fts WHERE page_text_fts MATCH '80211*'").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
    }
}
