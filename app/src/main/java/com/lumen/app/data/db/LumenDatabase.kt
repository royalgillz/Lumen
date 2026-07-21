package com.lumen.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lumen.app.data.db.dao.DocumentDao
import com.lumen.app.data.db.dao.PageDao
import com.lumen.app.data.db.dao.PageTextDao
import com.lumen.app.data.db.entity.DocumentEntity
import com.lumen.app.data.db.entity.PageEntity
import com.lumen.app.data.db.entity.PageTextEntity
import com.lumen.app.data.db.entity.PageTextFtsEntity

@Database(
    entities = [
        DocumentEntity::class,
        PageEntity::class,
        PageTextEntity::class,
        PageTextFtsEntity::class,
    ],
    version = 7,
    exportSchema = true
)
abstract class LumenDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun pageDao(): PageDao
    abstract fun pageTextDao(): PageTextDao

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

        // Page-level FTS: multi-word AND queries must match words anywhere on the
        // same page, not the same line. Backfills page text from existing lines so
        // no re-index is required; snippet aesthetics may differ slightly from a
        // fresh index (line concat vs raw extractor text) — search behaviour doesn't.
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `page_text` (" +
                        "`pageId` INTEGER NOT NULL, `text` TEXT NOT NULL, " +
                        "PRIMARY KEY(`pageId`), " +
                        "FOREIGN KEY(`pageId`) REFERENCES `pages`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                database.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `page_text_fts` " +
                        "USING FTS4(`text` TEXT NOT NULL, tokenize=unicode61, content=`page_text`)"
                )
                database.execSQL(
                    "INSERT INTO page_text(pageId, text) " +
                        "SELECT pageId, group_concat(text, char(10)) FROM (" +
                        "SELECT pageId, text FROM lines ORDER BY pageId, lineNumber" +
                        ") GROUP BY pageId"
                )
                database.execSQL("INSERT INTO page_text_fts(page_text_fts) VALUES('rebuild')")
            }
        }

        // The line-level tables are write-only once search moved to page_text
        // (5→6). Dropping them roughly halves index storage. Runs after 5→6,
        // which is the last reader of `lines`.
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DROP TABLE IF EXISTS `lines_fts`")
                database.execSQL("DROP TABLE IF EXISTS `lines`")
            }
        }
    }
}
