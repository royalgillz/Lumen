package com.lumen.app.domain.model

data class IndexingProgress(
    val totalFiles: Int,
    val processedFiles: Int,
    val currentFilename: String?,
    val isRunning: Boolean
) {
    val fraction: Float
        get() = if (totalFiles > 0) processedFiles.toFloat() / totalFiles else 0f
}
