package com.lumen.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "lines",
    foreignKeys = [ForeignKey(
        entity = PageEntity::class,
        parentColumns = ["id"],
        childColumns = ["pageId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("pageId")]
)
data class LineContentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pageId: Long,
    val lineNumber: Int,
    val text: String,
    val bboxJson: String? = null
)
