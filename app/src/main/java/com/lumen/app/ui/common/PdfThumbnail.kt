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
import com.lumen.app.data.pdf.MuPdfGate
import com.lumen.app.data.pdf.pixmapToBitmap
import com.lumen.app.data.pdf.withMuPdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
                    // Share the global render permit; render WITH alpha and let
                    // pixmapToBitmap composite over white so soft-masked icons don't
                    // collapse into solid colour blocks.
                    MuPdfGate.withRenderPermit {
                        val pixmap = runCatching {
                            page.toPixmap(matrix, ColorSpace.DeviceRGB, true)
                        }.getOrNull() ?: return@withRenderPermit null
                        try {
                            pixmapToBitmap(pixmap)
                        } finally {
                            runCatching { pixmap.destroy() }
                        }
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
