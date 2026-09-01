package com.lumen.app.data.fs

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File

/**
 * Ensures `Download/Lumen-pdfs` exists for the guided LUMEN_PDFS_FLOW: the
 * user moves Download-root PDFs into it, then grants it as a library folder
 * (the Download root itself is ungrantable on API >= 30, but a subfolder is).
 *
 * Creation strategy, in order:
 *  1. Direct `mkdirs()` under the public Downloads directory — on API 30+
 *     FUSE, creating a subfolder of Downloads needs no permission and leaves
 *     no artifact.
 *  2. Fallback: insert one small "Read me.txt" via MediaStore.Downloads with
 *     RELATIVE_PATH `Download/Lumen-pdfs` — MediaStore only materializes
 *     folders as a side effect of inserting a file, hence the readme. Queried
 *     first so it is never duplicated.
 *
 * Idempotent: an existing folder or readme short-circuits to success. Only
 * ever called on API >= 30 — the flow does not exist below that (asserted).
 *
 * No PDF is ever copied or moved by Lumen itself; the user performs the move
 * in their file manager. This helper only creates the empty destination.
 */
// @spec LIB-EXT-011
object LumenPdfsFolder {

    const val FOLDER_NAME = "Lumen-pdfs"

    /** MediaStore RELATIVE_PATH (stored with a trailing slash on disk). */
    const val MEDIA_STORE_RELATIVE_PATH = "Download/$FOLDER_NAME"

    /** External-storage-provider document id of the folder. */
    const val DOCUMENT_ID = "primary:Download/$FOLDER_NAME"

    const val README_NAME = "Read me.txt"
    const val README_CONTENT = "Move PDFs here and Lumen will index them."

    /**
     * Makes sure the folder exists, returning whether it is available. Never
     * throws on storage failure — a false return degrades the flow to the
     * plain KEEP_FILE offer.
     */
    @RequiresApi(30)
    fun ensureExists(context: Context): Boolean {
        check(Build.VERSION.SDK_INT >= 30) { "Lumen-pdfs flow is API 30+ only" }
        if (runCatching { ensureViaFile() }.getOrDefault(false)) return true
        return runCatching { ensureViaMediaStore(context) }.getOrDefault(false)
    }

    private fun ensureViaFile(): Boolean {
        val downloads =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                ?: return false
        val dir = File(downloads, FOLDER_NAME)
        if (dir.isDirectory) return true
        dir.mkdirs()
        return dir.isDirectory // verify, don't trust mkdirs' return alone
    }

    @RequiresApi(30)
    private fun ensureViaMediaStore(context: Context): Boolean {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        // Idempotency: the readme is only a vehicle for the folder — if one is
        // already indexed at this path the folder exists; never insert twice.
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(README_NAME, "$MEDIA_STORE_RELATIVE_PATH/"),
            null,
        )?.use { cursor ->
            if (cursor.count > 0) return true
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, README_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, MEDIA_STORE_RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val item = resolver.insert(collection, values) ?: return false
        val written = runCatching {
            resolver.openOutputStream(item)?.use { out ->
                out.write(README_CONTENT.toByteArray(Charsets.UTF_8))
                true
            } == true
        }.getOrDefault(false)
        if (!written) {
            runCatching { resolver.delete(item, null, null) }
            return false
        }
        resolver.update(
            item,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null,
        )
        return true
    }
}
