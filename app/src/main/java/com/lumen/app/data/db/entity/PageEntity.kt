package com.lumen.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pages",
    foreignKeys = [ForeignKey(
        entity = DocumentEntity::class,
        parentColumns = ["id"],
        childColumns = ["docId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("docId")]
)
data class PageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val docId: Long,
    val pageNumber: Int,
    val isOcr: Boolean = false,
    val wordCount: Int = 0,
    // For OCR pages: JSON of per-word boxes (normalised 0..1) so the viewer can
    // highlight matches on scanned pages that have no PDF text layer. Null otherwise.
    val wordBoxesJson: String? = null,
)
