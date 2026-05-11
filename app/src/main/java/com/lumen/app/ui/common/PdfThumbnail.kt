package com.lumen.app.ui.common

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Matrix
import com.lumen.app.data.pdf.withMuPdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

@Composable
fun PdfThumbnail(
    uriString: String,
    pageIndex: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bitmapState = produceState<Bitmap?>(initialValue = null, uriString, pageIndex) {
        val parsed = runCatching { Uri.parse(uriString) }.getOrNull()
        value = if (parsed == null) null else withContext(Dispatchers.IO) {
            withMuPdfDocument(context, parsed) { doc ->
                val count = doc.countPages()
                if (count <= 0) return@withMuPdfDocument null
                val safeIndex = pageIndex.coerceIn(0, count - 1)
                val page = runCatching { doc.loadPage(safeIndex) }.getOrNull()
                    ?: return@withMuPdfDocument null
                try {
                    val bounds = page.bounds
                    val pageWidthPts = bounds.x1 - bounds.x0
                    val pageHeightPts = bounds.y1 - bounds.y0
                    if (pageWidthPts <= 0f || pageHeightPts <= 0f) return@withMuPdfDocument null
                    val targetPx = 96f
                    val scale = (targetPx / pageWidthPts).coerceAtLeast(0.01f)
                    val matrix = Matrix(scale, scale)
                    val pixmap = runCatching {
                        page.toPixmap(matrix, ColorSpace.DeviceRGB, true)
                    }.getOrNull() ?: return@withMuPdfDocument null
                    try {
                        val w = pixmap.width
                        val h = pixmap.height
                        if (w <= 0 || h <= 0) return@withMuPdfDocument null
                        val samples = pixmap.samples ?: return@withMuPdfDocument null
                        val expected = w * h * 4
                        if (samples.size < expected) return@withMuPdfDocument null
                        val bmp = try {
                            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        } catch (_: OutOfMemoryError) {
                            return@withMuPdfDocument null
                        }
                        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(samples, 0, expected))
                        bmp
                    } finally {
                        runCatching { pixmap.destroy() }
                    }
                } finally {
                    runCatching { page.destroy() }
                }
            }
        }
    }

    if (bitmapState.value != null) {
        Image(
            bitmap = bitmapState.value!!.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
        )
    } else {
        Box(
            modifier = modifier
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.PictureAsPdf,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxSize(0.45f)
                    .padding(2.dp),
            )
        }
    }
}
