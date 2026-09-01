package com.lumen.app.data.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Page
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import com.lumen.app.data.ocr.OcrStrips
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PdfPageRenderer @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** One delivery from [renderPagesInSession]: a normal page rendered whole,
     *  or one full-width strip of an extreme-aspect page (see [OcrStrips]).
     *  Strips of a page arrive top-to-bottom; the final one has `band.isLast`. */
    sealed class OcrPiece {
        class WholePage(val bitmap: Bitmap) : OcrPiece()
        class Strip(
            val bitmap: Bitmap,
            val band: OcrStrips.Band,
            val pageWPt: Float,
            val pageHPt: Float,
            val scale: Float,
        ) : OcrPiece()
    }

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
                MuPdfGate.withRenderPermit { renderPageInternal(doc, pageIndex, dpi) }
            }
        }

    /**
     * Opens the PDF once and delivers each index in [pageIndices], in order,
     * as [OcrPiece]s. Normal pages arrive whole; a page past [OcrStrips]'
     * aspect/size thresholds arrives as full-width strips so no single OCR
     * bitmap ever exceeds the strip budget — rendering a GoodNotes-style
     * page whole allocates in the hundred-MB class and fails outright.
     * Every bitmap is recycled right after the callback returns; do not
     * retain it.
     */
    // @spec LIB-BIG-001
    suspend fun renderPagesInSession(
        uri: Uri,
        pageIndices: List<Int>,
        dpi: Int = 120,
        onPiece: suspend (pageIndex: Int, piece: OcrPiece) -> Unit,
    ) {
        if (pageIndices.isEmpty()) return
        withContext(Dispatchers.IO) {
            withMuPdfDocument(context, uri) { doc ->
                val count = doc.countPages()
                val scale = dpi / 72f
                for (pageIndex in pageIndices) {
                    if (pageIndex !in 0 until count) continue
                    val size = pageSizePt(doc, pageIndex) ?: continue
                    val (wPt, hPt) = size
                    if (OcrStrips.needsStrips(wPt, hPt, scale)) {
                        for (band in OcrStrips.bands(hPt, scale)) {
                            // Acquire per strip so a long OCR run can't hold the
                            // permit and starve the viewer between pieces.
                            val bitmap = MuPdfGate.withRenderPermit {
                                renderStripInternal(doc, pageIndex, scale, band.renderTopPt, band.renderBottomPt)
                            } ?: continue
                            try {
                                onPiece(pageIndex, OcrPiece.Strip(bitmap, band, wPt, hPt, scale))
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    } else {
                        val bitmap = MuPdfGate.withRenderPermit {
                            renderPageInternal(doc, pageIndex, dpi)
                        } ?: continue
                        try {
                            onPiece(pageIndex, OcrPiece.WholePage(bitmap))
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        }
    }

    private fun pageSizePt(doc: Document, pageIndex: Int): Pair<Float, Float>? {
        val page: Page = try {
            doc.loadPage(pageIndex)
        } catch (_: Throwable) {
            return null
        }
        return try {
            val b = page.bounds
            (b.x1 - b.x0) to (b.y1 - b.y0)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { page.destroy() }
        }
    }

    private fun renderPageInternal(doc: Document, pageIndex: Int, dpi: Int): Bitmap? {
        val page: Page = try {
            doc.loadPage(pageIndex)
        } catch (_: Throwable) {
            return null
        }
        return try {
            val scale = dpi / 72f
            val matrix = Matrix(scale, scale)
            // Native render straight into an opaque white-backed ARGB_8888 bitmap;
            // see MuPdfPageRenderer.renderPage for why no Pixmap round-trip.
            AndroidDrawDevice.drawPage(page, matrix)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { page.destroy() }
        }
    }

    /** Full-width band [topPt, bottomPt) of a page at [scale], patch-rendered:
     *  the ctm maps the whole page to device pixels and the draw device's
     *  origin is offset to the band, so only the band rasterises. */
    // @spec LIB-BIG-001
    private fun renderStripInternal(
        doc: Document,
        pageIndex: Int,
        scale: Float,
        topPt: Float,
        bottomPt: Float,
    ): Bitmap? {
        if (bottomPt <= topPt) return null
        val page: Page = try {
            doc.loadPage(pageIndex)
        } catch (_: Throwable) {
            return null
        }
        return try {
            val b = page.bounds
            val w = ((b.x1 - b.x0) * scale).toInt().coerceAtLeast(1)
            val h = ((bottomPt - topPt) * scale).toInt().coerceAtLeast(1)
            val ctm = Matrix(scale, 0f, 0f, scale, -b.x0 * scale, -b.y0 * scale)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            val dev = AndroidDrawDevice(bitmap, 0, (topPt * scale).toInt())
            try {
                page.run(dev, ctm, null)
                dev.close()
                bitmap
            } catch (_: Throwable) {
                bitmap.recycle()
                null
            } finally {
                runCatching { dev.destroy() }
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { page.destroy() }
        }
    }
}
