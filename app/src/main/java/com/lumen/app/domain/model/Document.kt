package com.lumen.app.domain.model

data class Document(
    val id: Long,
    val uri: String,
    val filename: String,
    val status: String,
    val pageCount: Int,
    val sizeBytes: Long,
    val addedAt: Long,
    val indexedAt: Long?
)
