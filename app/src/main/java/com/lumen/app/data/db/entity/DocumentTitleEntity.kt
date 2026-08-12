package com.lumen.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user's rename, keyed by document URI like bookmarks — it survives
 * re-indexing, vanished-file cleanup, folder remove-and-re-add, and index
 * deletion. The indexer never touches this table.
 */
// @spec LIB-TTL-005
@Entity(tableName = "document_titles")
data class DocumentTitleEntity(
    @PrimaryKey val docUri: String,
    val title: String,
)
