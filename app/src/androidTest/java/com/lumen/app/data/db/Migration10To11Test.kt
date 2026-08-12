package com.lumen.app.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration10To11Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LumenDatabase::class.java,
    )

    private val dbName = "migration-10-11-test"

    // @spec LIB-TTL-002
    @Test
    fun migrate10To11_addsTitleTableAndDerivedTitleColumn() {
        helper.createDatabase(dbName, 10).use { db ->
            db.execSQL(
                "INSERT INTO documents (uri, filename, treeUri, status, pageCount, lastModified, addedAt, sizeBytes) " +
                    "VALUES ('content://d/1', 'a.pdf', 'content://tree/t', 'indexed', 1, 0, 0, 0)"
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 11, true, LumenDatabase.MIGRATION_10_11)

        db.query("SELECT derivedTitle FROM documents WHERE uri = 'content://d/1'").use { c ->
            c.moveToFirst()
            assertTrue("existing rows must have null derivedTitle (backfill fills them)", c.isNull(0))
        }
        db.execSQL("INSERT INTO document_titles (docUri, title) VALUES ('content://d/1', 'My Title')")
        db.query("SELECT count(*) FROM document_titles").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
    }
}
