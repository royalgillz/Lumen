package com.lumen.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val filename: String,
    @ColumnInfo(defaultValue = "") val treeUri: String = "",
    val status: String = STATUS_PENDING,
    val pageCount: Int = 0,
    val lastModified: Long = 0L,
    val addedAt: Long = System.currentTimeMillis(),
    val indexedAt: Long? = null,
    val sizeBytes: Long = 0L,
    /** When the viewer last opened this document — user recency, distinct from
     *  indexedAt (system recency). Null until first opened. */
    val lastOpenedAt: Long? = null,
    /** Auto-extracted display title. Null = never attempted; "" = attempted,
     *  nothing trustworthy found (DocumentTitles.NONE). Recomputed on index. */
    val derivedTitle: String? = null,
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_INDEXING = "indexing"
        const val STATUS_INDEXED = "indexed"
        const val STATUS_ENCRYPTED = "encrypted"
        const val STATUS_ERROR = "error"
    }
}

/**
 * The re-index upsert's entity merge: the indexer owns extraction columns
 * (status, pageCount, derivedTitle, …) and must preserve identity and
 * user-recency columns from the existing row — the REPLACE strategy wipes
 * anything not threaded through (this once silently reset lastOpenedAt on
 * every rescan).
 */
// @spec LIB-TTL-009
fun mergeForReindex(existing: DocumentEntity?, fresh: DocumentEntity): DocumentEntity =
    if (existing == null) {
        fresh
    } else {
        fresh.copy(
            id = existing.id,
            addedAt = existing.addedAt,
            lastOpenedAt = existing.lastOpenedAt,
        )
    }
