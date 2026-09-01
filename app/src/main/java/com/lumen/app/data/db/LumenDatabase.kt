package com.lumen.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lumen.app.data.db.dao.BookmarkDao
import com.lumen.app.data.db.dao.DocumentDao
import com.lumen.app.data.db.dao.DocumentTitleDao
import com.lumen.app.data.db.dao.ExternalOpenDao
import com.lumen.app.data.db.dao.PageDao
import com.lumen.app.data.db.dao.PageTextDao
import com.lumen.app.data.db.dao.RenameDao
import com.lumen.app.data.db.entity.BookmarkEntity
import com.lumen.app.data.db.entity.DocumentEntity
import com.lumen.app.data.db.entity.DocumentTitleEntity
import com.lumen.app.data.db.entity.ExternalOpenEntity
import com.lumen.app.data.db.entity.PageEntity
import com.lumen.app.data.db.entity.PageTextEntity
import com.lumen.app.data.db.entity.PageTextFtsEntity
import com.lumen.app.data.text.TextNormalizer

@Database(
    entities = [
        DocumentEntity::class,
        PageEntity::class,
        PageTextEntity::class,
        PageTextFtsEntity::class,
        BookmarkEntity::class,
        DocumentTitleEntity::class,
        ExternalOpenEntity::class,
    ],
    version = 14,
    exportSchema = true
)
abstract class LumenDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun pageDao(): PageDao
    abstract fun pageTextDao(): PageTextDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun documentTitleDao(): DocumentTitleDao
    abstract fun externalOpenDao(): ExternalOpenDao
    abstract fun renameDao(): RenameDao

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

        // Normalized search text: adds page_text.textNorm (backfilled in SQL from
        // TextNormalizer's replace chain) and points the FTS index at it, so
        // punctuated identifiers (F-1, 802.11) match their compact forms. Pure
        // SQL inside the migration transaction: no window where search runs
        // against an unbuilt index, and interruption rolls back cleanly.
        // @spec SEARCH-IDX-001, SEARCH-IDX-002
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE page_text ADD COLUMN textNorm TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    "UPDATE page_text SET textNorm = ${TextNormalizer.sqlNormExpr("text")}"
                )
                database.execSQL("DROP TABLE IF EXISTS `page_text_fts`")
                database.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `page_text_fts` " +
                        "USING FTS4(`textNorm` TEXT NOT NULL, tokenize=unicode61, content=`page_text`)"
                )
                database.execSQL("INSERT INTO page_text_fts(page_text_fts) VALUES('rebuild')")
            }
        }

        // Display titles: URI-keyed rename store (survives row deletion, like
        // bookmarks) + the derived-title column the indexer recomputes.
        // @spec LIB-TTL-002
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE documents ADD COLUMN derivedTitle TEXT")
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `document_titles` " +
                        "(`docUri` TEXT NOT NULL, `title` TEXT NOT NULL, PRIMARY KEY(`docUri`))"
                )
            }
        }

        // External-open recency: VIEW-intent opens with no library row. Outside
        // `documents` so canonical counts never see it; purely additive.
        // @spec LIB-REC-003
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `external_opens` (" +
                        "`docUri` TEXT NOT NULL, " +
                        "`displayName` TEXT NOT NULL, " +
                        "`lastOpenedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`docUri`))"
                )
            }
        }

        // Embedded PDF metadata author. Nullable and additive — existing rows
        // stay null until their next re-index writes the value.
        // @spec LIB-TTL-011
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE documents ADD COLUMN author TEXT")
            }
        }

        // External-access honesty + ephemeral indexing. external_opens learns
        // whether a persistable grant is actually held (persisted), that a
        // reopen died with the grant (accessLost — shown expired, not deleted),
        // and that the make-permanent offer was dismissed (offerDismissed —
        // offers appear once). documents gains the nullable rolling-TTL column
        // ephemeralExpiresAt (null = permanent library doc). All additive with
        // defaults matching prior behavior — existing rows are unaffected.
        // @spec LIB-EXT-006
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE external_opens ADD COLUMN persisted INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE external_opens ADD COLUMN accessLost INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE external_opens ADD COLUMN offerDismissed INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE documents ADD COLUMN ephemeralExpiresAt INTEGER"
                )
            }
        }

        // User recency: when the viewer last opened each document. Nullable and
        // additive — never-opened documents simply have no recency.
        // @spec LIB-REC-002
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE documents ADD COLUMN lastOpenedAt INTEGER")
            }
        }

        // Reader bookmarks. Purely additive; keyed by document URI so they work
        // for never-indexed documents and survive index deletion.
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bookmarks` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`docUri` TEXT NOT NULL, " +
                        "`pageNumber` INTEGER NOT NULL, " +
                        "`note` TEXT, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_bookmarks_docUri_pageNumber` " +
                        "ON `bookmarks` (`docUri`, `pageNumber`)"
                )
            }
        }
    }
}
