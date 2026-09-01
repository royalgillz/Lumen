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

/** A directory the provider could not enumerate (null cursor). Fatal for the
 *  whole scan: the caller must abort rather than mistake it for "no files". */
class ScanFailedException(directoryUri: Uri) :
    Exception("Provider returned no cursor for $directoryUri")

@Singleton
class PdfScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** Recursively walks a SAF document tree and returns all PDF files found.
     *  A folder deleted from disk scans as empty (its index rows must empty out
     *  too); a provider that cannot enumerate a *live* tree aborts instead.
     *  @throws ScanFailedException when any directory cannot be enumerated
     *  while the tree root still exists — never returns a partial listing. */
    fun scanTree(treeUri: Uri): List<PdfFile> {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
        return try {
            collectPdfs(treeUri, rootDocUri)
        } catch (e: ScanFailedException) {
            // Distinguish "folder deleted" from "provider unavailable": only a
            // verifiably-gone root may report empty (letting vanished-document
            // cleanup run); anything inconclusive aborts — wiping a live
            // folder's index over a provider hiccup costs a full re-extraction.
            if (rootExists(rootDocUri)) throw e else emptyList()
        }
    }

    /** True unless the tree's root document is verifiably gone. A null cursor
     *  or an unexpected error is inconclusive (counts as existing, so the scan
     *  aborts upstream); only a no-row result or FileNotFoundException proves
     *  deletion. */
    private fun rootExists(rootDocUri: Uri): Boolean {
        return try {
            val cursor = context.contentResolver.query(
                rootDocUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null, null, null,
            ) ?: return true
            cursor.use { it.moveToFirst() }
        } catch (_: java.io.FileNotFoundException) {
            false
        } catch (_: Exception) {
            true
        }
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

        // A null cursor is a dead/unavailable provider, NOT an empty directory —
        // treating it as empty would hand the caller an empty child set and let
        // vanished-document cleanup wipe the folder's (or subfolder's) index.
        // @spec LIB-IDX-002
        val childCursor = context.contentResolver.query(childrenUri, projection, null, null, null)
            ?: throw ScanFailedException(childrenUri)

        childCursor.use { cursor ->
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
