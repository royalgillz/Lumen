package com.lumen.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lumen.app.data.db.dao.DocumentDao
import com.lumen.app.data.db.dao.LineDao
import com.lumen.app.data.db.dao.PageDao
import com.lumen.app.data.db.entity.DocumentEntity
import com.lumen.app.data.db.entity.LineContentEntity
import com.lumen.app.data.db.entity.LineEntity
import com.lumen.app.data.db.entity.PageEntity

@Database(
    entities = [
        DocumentEntity::class,
        PageEntity::class,
        LineContentEntity::class,
        LineEntity::class,
    ],
    version = 5,
    exportSchema = true
)
abstract class LumenDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun pageDao(): PageDao
    abstract fun lineDao(): LineDao

    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE documents ADD COLUMN treeUri TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        // Adds OCR word boxes. Non-destructive so the existing index is preserved;
        // already-indexed scanned pages simply have null boxes (no highlight) until
        // they are re-indexed.
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE pages ADD COLUMN wordBoxesJson TEXT")
            }
        }
    }
}
