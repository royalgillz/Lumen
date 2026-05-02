package com.lumen.app.data.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
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
     * Renders [pageIndex] (0-based) of the PDF at [uri] to a Bitmap at [dpi] resolution.
     * Uses ParcelFileDescriptor so no copy of the file is made.
     * Returns null on any failure.
     */
    suspend fun renderPage(uri: Uri, pageIndex: Int, dpi: Int = 120): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        if (pageIndex >= renderer.pageCount) return@withContext null
                        renderBitmap(renderer, pageIndex, dpi)
                    }
                }
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Opens the PDF once and calls [onPage] for each index in [pageIndices] in order.
     * The bitmap passed to [onPage] is recycled immediately after it returns — do not
     * hold a reference past the callback.
     */
    suspend fun renderPagesInSession(
        uri: Uri,
        pageIndices: List<Int>,
        dpi: Int = 120,
        onPage: suspend (pageIndex: Int, bitmap: Bitmap) -> Unit,
    ) = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    for (pageIndex in pageIndices) {
                        if (pageIndex >= renderer.pageCount) continue
                        val bitmap = renderBitmap(renderer, pageIndex, dpi) ?: continue
                        try {
                            onPage(pageIndex, bitmap)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun renderBitmap(renderer: PdfRenderer, pageIndex: Int, dpi: Int): Bitmap? {
        return try {
            renderer.openPage(pageIndex).use { page ->
                val scale = dpi / 72f
                val width = (page.width * scale).toInt().coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        } catch (_: Exception) { null }
    }
}
