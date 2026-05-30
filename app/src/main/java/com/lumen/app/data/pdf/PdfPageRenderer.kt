package com.lumen.app.data.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Page
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PdfPageRenderer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Renders [pageIndex] (0-based) of the PDF at [uri] to a Bitmap at [dpi]
     * resolution via MuPDF. Returns null on any failure.
     *
     * Reads the file in place via SAF + a seekable PFD wrapper — no copy is made.
     */
    suspend fun renderPage(uri: Uri, pageIndex: Int, dpi: Int = 120): Bitmap? =
        withContext(Dispatchers.IO) {
            withMuPdfDocument(context, uri) { doc ->
                if (pageIndex !in 0 until doc.countPages()) return@withMuPdfDocument null
                renderPageInternal(doc, pageIndex, dpi)
            }
        }

    /**
     * Opens the PDF once and calls [onPage] for each index in [pageIndices] in
     * order. The bitmap passed to [onPage] is recycled immediately after the
     * callback returns; do not retain it.
     */
    suspend fun renderPagesInSession(
        uri: Uri,
        pageIndices: List<Int>,
        dpi: Int = 120,
        onPage: suspend (pageIndex: Int, bitmap: Bitmap) -> Unit,
    ) {
        if (pageIndices.isEmpty()) return
        withContext(Dispatchers.IO) {
            withMuPdfDocument(context, uri) { doc ->
                val count = doc.countPages()
                for (pageIndex in pageIndices) {
                    if (pageIndex !in 0 until count) continue
                    val bitmap = renderPageInternal(doc, pageIndex, dpi) ?: continue
                    try {
                        onPage(pageIndex, bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    private suspend fun renderPageInternal(doc: Document, pageIndex: Int, dpi: Int): Bitmap? {
        val page: Page = try {
            doc.loadPage(pageIndex)
        } catch (_: Throwable) {
            return null
        }
        return try {
            val scale = dpi / 72f
            val matrix = Matrix(scale, scale)
            val pixmap = try {
                page.toPixmap(matrix, ColorSpace.DeviceRGB, /* alpha = */ false)
            } catch (_: OutOfMemoryError) {
                return null
            } catch (_: Throwable) {
                return null
            } ?: return null
            try {
                pixmapToBitmap(pixmap)
            } finally {
                runCatching { pixmap.destroy() }
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { page.destroy() }
        }
    }
}
