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
    val wordCount: Int = 0
)
