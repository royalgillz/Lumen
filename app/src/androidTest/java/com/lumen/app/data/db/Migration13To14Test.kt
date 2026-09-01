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
class Migration13To14Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LumenDatabase::class.java,
    )

    private val dbName = "migration-13-14-test"

    // @spec LIB-EXT-006
    @Test
    fun migrate13To14_addsAccessFlagsAndEphemeralExpiry() {
        helper.createDatabase(dbName, 13).use { db ->
            db.execSQL(
                "INSERT INTO external_opens (docUri, displayName, lastOpenedAt) " +
                    "VALUES ('content://ext/a.pdf', 'a.pdf', 100)"
            )
            db.execSQL(
                "INSERT INTO documents (uri, filename, treeUri, status, pageCount, lastModified, addedAt, sizeBytes) " +
                    "VALUES ('content://d/1', 'a.pdf', 'content://tree/t', 'indexed', 1, 0, 0, 0)"
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 14, true, LumenDatabase.MIGRATION_13_14)

        // Existing external rows: no grant assumed, not expired, offer never dismissed.
        db.query(
            "SELECT persisted, accessLost, offerDismissed FROM external_opens " +
                "WHERE docUri = 'content://ext/a.pdf'"
        ).use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
            assertEquals(0, c.getInt(1))
            assertEquals(0, c.getInt(2))
        }

        // Existing documents stay permanent library citizens.
        db.query("SELECT ephemeralExpiresAt FROM documents WHERE uri = 'content://d/1'").use { c ->
            c.moveToFirst()
            assertTrue("existing rows must stay non-ephemeral", c.isNull(0))
        }

        db.execSQL(
            "UPDATE external_opens SET persisted = 1, accessLost = 1, offerDismissed = 1 " +
                "WHERE docUri = 'content://ext/a.pdf'"
        )
        db.query(
            "SELECT persisted, accessLost, offerDismissed FROM external_opens " +
                "WHERE docUri = 'content://ext/a.pdf'"
        ).use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
            assertEquals(1, c.getInt(1))
            assertEquals(1, c.getInt(2))
        }

        db.execSQL("UPDATE documents SET ephemeralExpiresAt = 999 WHERE uri = 'content://d/1'")
        db.query("SELECT ephemeralExpiresAt FROM documents WHERE uri = 'content://d/1'").use { c ->
            c.moveToFirst()
            assertEquals(999L, c.getLong(0))
        }
    }
}
