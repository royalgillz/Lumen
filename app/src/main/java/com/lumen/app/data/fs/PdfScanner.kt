package com.lumen.app.data.fs

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class PdfFile(
    val uri: Uri,
    val filename: String,
    val lastModified: Long,
    val sizeBytes: Long,
)

@Singleton
class PdfScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** Recursively walks a SAF document tree and returns all PDF files found. */
    fun scanTree(treeUri: Uri): List<PdfFile> {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
        return collectPdfs(treeUri, rootDocUri)
    }

    private fun collectPdfs(treeUri: Uri, parentDocUri: Uri): List<PdfFile> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getDocumentId(parentDocUri)
        )

        val results = mutableListOf<PdfFile>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )

        context.contentResolver.query(childrenUri, projection, null, null, null)
            ?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val modCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val sizeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)

                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idCol)
                    val name = cursor.getString(nameCol) ?: continue
                    val mime = cursor.getString(mimeCol) ?: continue
                    val modified = cursor.getLong(modCol)
                    val size = cursor.getLong(sizeCol)

                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

                    when {
                        mime == DocumentsContract.Document.MIME_TYPE_DIR ->
                            results += collectPdfs(treeUri, docUri)

                        mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true) ->
                            results += PdfFile(docUri, name, modified, size)
                    }
                }
            }

        return results
    }
}
