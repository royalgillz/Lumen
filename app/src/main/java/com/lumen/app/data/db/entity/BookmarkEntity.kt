package com.lumen.app.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A reader's bookmark: "I was at this page on this date", with an optional note.
 *
 * Keyed by the document's URI string rather than a documents-table id so
 * bookmarks also work for PDFs opened directly (VIEW intents) that were never
 * indexed, and survive "Delete Search Index" — they are user data, not index
 * data. One bookmark per page per document (adding again toggles it off).
 */
@Entity(
    tableName = "bookmarks",
    indices = [Index(value = ["docUri", "pageNumber"], unique = true)],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val docUri: String,
    /** 0-indexed, like every other page number in the app; display as +1. */
    val pageNumber: Int,
    val note: String? = null,
    val createdAt: Long,
)
