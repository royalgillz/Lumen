package com.lumen.app.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration9To10Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LumenDatabase::class.java,
    )

    private val dbName = "migration-9-10-test"

    // @spec LIB-REC-002
    @Test
    fun migrate9To10_addsNullableLastOpenedAt() {
        helper.createDatabase(dbName, 9).use { db ->
            db.execSQL(
                "INSERT INTO documents (uri, filename, treeUri, status, pageCount, lastModified, addedAt, sizeBytes) " +
                    "VALUES ('content://d/1', 'a.pdf', 'content://tree/t', 'indexed', 1, 0, 0, 0)"
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 10, true, LumenDatabase.MIGRATION_9_10)

        db.query("SELECT lastOpenedAt FROM documents WHERE uri = 'content://d/1'").use { c ->
            c.moveToFirst()
            assertTrue("existing rows must have null lastOpenedAt", c.isNull(0))
        }
    }
}
