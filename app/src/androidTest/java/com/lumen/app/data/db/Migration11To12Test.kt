package com.lumen.app.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration11To12Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LumenDatabase::class.java,
    )

    private val dbName = "migration-11-12-test"

    // @spec LIB-REC-003
    @Test
    fun migrate11To12_createsExternalOpensTable() {
        helper.createDatabase(dbName, 11).close()

        val db = helper.runMigrationsAndValidate(dbName, 12, true, LumenDatabase.MIGRATION_11_12)

        db.execSQL(
            "INSERT INTO external_opens (docUri, displayName, lastOpenedAt) " +
                "VALUES ('content://ext/1', 'Visa Appointment.pdf', 42)"
        )
        db.query("SELECT displayName, lastOpenedAt FROM external_opens WHERE docUri = 'content://ext/1'").use { c ->
            c.moveToFirst()
            assertEquals("Visa Appointment.pdf", c.getString(0))
            assertEquals(42L, c.getLong(1))
        }
    }
}
