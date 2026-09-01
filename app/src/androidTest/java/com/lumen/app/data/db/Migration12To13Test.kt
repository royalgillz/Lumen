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
class Migration12To13Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LumenDatabase::class.java,
    )

    private val dbName = "migration-12-13-test"

    // @spec LIB-TTL-011
    @Test
    fun migrate12To13_addsNullableAuthor() {
        helper.createDatabase(dbName, 12).use { db ->
            db.execSQL(
                "INSERT INTO documents (uri, filename, treeUri, status, pageCount, lastModified, addedAt, sizeBytes) " +
                    "VALUES ('content://d/1', 'a.pdf', 'content://tree/t', 'indexed', 1, 0, 0, 0)"
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 13, true, LumenDatabase.MIGRATION_12_13)

        db.query("SELECT author FROM documents WHERE uri = 'content://d/1'").use { c ->
            c.moveToFirst()
            assertTrue("existing rows must have null author", c.isNull(0))
        }

        db.execSQL("UPDATE documents SET author = 'Jane Doe' WHERE uri = 'content://d/1'")
        db.query("SELECT author FROM documents WHERE uri = 'content://d/1'").use { c ->
            c.moveToFirst()
            assertEquals("Jane Doe", c.getString(0))
        }
    }
}
