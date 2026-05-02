package com.lumen.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val filename: String,
    val status: String = STATUS_PENDING,
    val pageCount: Int = 0,
    val lastModified: Long = 0L,
    val addedAt: Long = System.currentTimeMillis(),
    val indexedAt: Long? = null,
    val sizeBytes: Long = 0L
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_INDEXING = "indexing"
        const val STATUS_INDEXED = "indexed"
        const val STATUS_ENCRYPTED = "encrypted"
        const val STATUS_ERROR = "error"
    }
}
