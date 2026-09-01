package com.lumen.app.data.fs

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A null children cursor is a dead/unavailable provider, not an empty
 * directory: the scan must fail loudly (never return a partial listing) so
 * IndexWorker's vanished-document cleanup cannot mistake it for "all files
 * deleted" and wipe the folder's index.
 */
// @spec LIB-IDX-002
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfScannerRoboTest {

    private lateinit var scanner: PdfScanner
    private val treeUri: Uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root")

    @Before
    fun setUp() {
        FakeDocsProvider.responses.clear()
        Robolectric.setupContentProvider(FakeDocsProvider::class.java, AUTHORITY)
        scanner = PdfScanner(ApplicationProvider.getApplicationContext<Context>())
    }

    @Test
    fun nullRootCursor_throwsInsteadOfReturningEmpty() {
        // No response registered for "root" — the provider answers null.
        assertThrows(ScanFailedException::class.java) {
            scanner.scanTree(treeUri)
        }
    }

    @Test
    fun nullSubfolderCursor_failsWholeScan_neverPartialListing() {
        FakeDocsProvider.responses["root"] = {
            cursorOf(
                row("a.pdf", "a.pdf", "application/pdf", 123L, 10L),
                row("sub", "Sub", DocumentsContract.Document.MIME_TYPE_DIR, 0L, 0L),
            )
        }
        // No response for "sub": the subfolder query answers null mid-walk.
        assertThrows(ScanFailedException::class.java) {
            scanner.scanTree(treeUri)
        }
    }

    @Test
    fun successfulWalk_returnsPdfsIncludingSubfolders() {
        FakeDocsProvider.responses["root"] = {
            cursorOf(
                row("a.pdf", "a.pdf", "application/pdf", 123L, 10L),
                row("sub", "Sub", DocumentsContract.Document.MIME_TYPE_DIR, 0L, 0L),
            )
        }
        FakeDocsProvider.responses["sub"] = {
            cursorOf(row("b.pdf", "b.pdf", "application/pdf", 456L, 20L))
        }

        val found = scanner.scanTree(treeUri)

        assertEquals(listOf("a.pdf", "b.pdf"), found.map { it.filename }.sorted())
    }

    // -- fixture plumbing ---------------------------------------------------

    private fun row(docId: String, name: String, mime: String, modified: Long, size: Long) =
        arrayOf<Any>(docId, name, mime, modified, size)

    private fun cursorOf(vararg rows: Array<Any>): Cursor {
        val cursor = MatrixCursor(
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE,
            )
        )
        rows.forEach { cursor.addRow(it) }
        return cursor
    }

    /** Answers children queries from [responses] keyed by parent document id;
     *  an unregistered id answers null, like a dead provider. */
    class FakeDocsProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?,
        ): Cursor? {
            // .../tree/<treeId>/document/<docId>/children
            val segments = uri.pathSegments
            val docId = segments[segments.indexOf("document") + 1]
            return responses[docId]?.invoke()
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<String>?,
        ): Int = 0

        companion object {
            val responses = mutableMapOf<String, () -> Cursor?>()
        }
    }

    private companion object {
        const val AUTHORITY = "com.lumen.test.docs"
    }
}
