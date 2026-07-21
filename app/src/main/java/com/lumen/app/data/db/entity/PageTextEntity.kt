package com.lumen.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.PrimaryKey

/**
 * Full text of one page, keyed by the page's row id (so the FTS rowid IS the
 * pageId — no extra join table). Backs [PageTextFtsEntity].
 *
 * Exists alongside the per-line index because multi-word AND queries evaluated
 * against individual lines miss words split across lines of the same page —
 * page granularity is what "all these words on one page" actually means.
 */
@Entity(
    tableName = "page_text",
    foreignKeys = [ForeignKey(
        entity = PageEntity::class,
        parentColumns = ["id"],
        childColumns = ["pageId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class PageTextEntity(
    @PrimaryKey val pageId: Long,
    val text: String,
)

// FTS4 virtual table backed by `page_text`. Room keeps it in sync via triggers.
@Fts4(
    contentEntity = PageTextEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
)
@Entity(tableName = "page_text_fts")
data class PageTextFtsEntity(
    val text: String
)
