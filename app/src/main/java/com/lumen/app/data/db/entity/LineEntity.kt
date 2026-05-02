package com.lumen.app.data.db.entity

import androidx.room.Entity
import androidx.room.Fts4

// FTS4 virtual table backed by the `lines` content table.
// Only `text` is declared here — that's the only column indexed for search.
// Room auto-generates INSERT/UPDATE/DELETE triggers on `lines` to keep the index in sync.
@Fts4(contentEntity = LineContentEntity::class)
@Entity(tableName = "lines_fts")
data class LineEntity(
    val text: String
)
