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
    suspend fun renderPage(uri: Uri, pageIndex: Int, dpi: Int = 150): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        if (pageIndex >= renderer.pageCount) return@withContext null
                        renderer.openPage(pageIndex).use { page ->
                            val scale = dpi / 72f
                            val width = (page.width * scale).toInt().coerceAtLeast(1)
                            val height = (page.height * scale).toInt().coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bitmap
                        }
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
}
