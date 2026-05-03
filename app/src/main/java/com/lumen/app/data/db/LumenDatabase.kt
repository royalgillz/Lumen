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
    version = 4,
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
    }
}
