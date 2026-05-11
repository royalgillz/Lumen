package com.lumen.app.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.util.SizeF
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Link
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Page
import com.artifex.mupdf.fitz.Rect
import com.lumen.app.data.pdf.PfdSeekableStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/**
 * Long-lived MuPDF session for the viewer.
 *
 * Holds one open [Document] (backed by a [PfdSeekableStream] over a SAF URI) and
 * exposes coroutine-friendly APIs to render pages, query layout, and traverse
 * links. MuPDF's fitz documents are not thread-safe, so all access is serialised
 * through an internal [Mutex].
 *
 * The session is created via [open]; the caller is responsible for [close]-ing
 * it when the viewer is destroyed.
 */
class MuPdfPageRenderer private constructor(
    private val stream: PfdSeekableStream,
    private val doc: Document,
    val pageCount: Int,
) : AutoCloseable {

    private val mutex = Mutex()
    private val pageBoundsCache: Array<SizeF?> = arrayOfNulls(pageCount)
    @Volatile private var closed = false

    data class LinkInfo(
        val bounds: RectF,
        val uri: String,
        val isExternal: Boolean,
        internal val raw: Link,
    )

    suspend fun pageSize(index: Int): SizeF? {
        if (index !in 0 until pageCount) return null
        pageBoundsCache[index]?.let { return it }
        return withDocLock {
            val page = runCatching { doc.loadPage(index) }.getOrNull() ?: return@withDocLock null
            try {
                val b: Rect = page.bounds
                val size = SizeF(b.x1 - b.x0, b.y1 - b.y0)
                pageBoundsCache[index] = size
                size
            } finally {
                page.destroy()
            }
        }
    }

    /**
     * Render [index] to a Bitmap. [scale] is the device-pixel multiplier on the
     * page's point dimensions (e.g. 1.0 = 1px-per-pt, 2.0 = double resolution).
     */
    suspend fun renderPage(index: Int, scale: Float): Bitmap? {
        if (index !in 0 until pageCount) return null
        if (scale <= 0f) return null
        return withDocLock {
            val page = runCatching { doc.loadPage(index) }.getOrNull() ?: return@withDocLock null
            try {
                val matrix = Matrix(scale, scale)
                val pixmap = runCatching {
                    page.toPixmap(matrix, ColorSpace.DeviceRGB, /* alpha = */ true)
                }.getOrNull() ?: return@withDocLock null
                try {
                    val w = pixmap.width
                    val h = pixmap.height
                    if (w <= 0 || h <= 0) return@withDocLock null
                    val samples = pixmap.samples ?: return@withDocLock null
                    val expected = w * h * 4
                    if (samples.size < expected) return@withDocLock null
                    val bmp = try {
                        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    } catch (_: OutOfMemoryError) {
                        return@withDocLock null
                    }
                    bmp.copyPixelsFromBuffer(ByteBuffer.wrap(samples, 0, expected))
                    bmp
                } finally {
                    pixmap.destroy()
                }
            } catch (_: Throwable) {
                null
            } finally {
                page.destroy()
            }
        }
    }

    suspend fun linksForPage(index: Int): List<LinkInfo> {
        if (index !in 0 until pageCount) return emptyList()
        return withDocLock {
            val page = runCatching { doc.loadPage(index) }.getOrNull() ?: return@withDocLock emptyList()
            try {
                val links = runCatching { page.links }.getOrNull() ?: return@withDocLock emptyList()
                links.mapNotNull { link ->
                    val bounds = link?.bounds ?: return@mapNotNull null
                    val uri = link.uri.orEmpty()
                    LinkInfo(
                        bounds = RectF(bounds.x0, bounds.y0, bounds.x1, bounds.y1),
                        uri = uri,
                        isExternal = isExternalUri(uri),
                        raw = link,
                    )
                }
            } finally {
                page.destroy()
            }
        } ?: emptyList()
    }

    /**
     * Resolve an internal-link target to a 0-indexed page number, or null if the
     * link is external or unresolvable.
     */
    suspend fun resolveLinkPage(link: LinkInfo): Int? {
        if (link.isExternal) return null
        return withDocLock {
            try {
                val loc = doc.resolveLink(link.raw) ?: return@withDocLock null
                val pageNum = doc.pageNumberFromLocation(loc)
                if (pageNum in 0 until pageCount) pageNum else null
            } catch (_: Throwable) {
                null
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { doc.destroy() }
        runCatching { stream.close() }
    }

    private suspend fun <R> withDocLock(block: () -> R): R? {
        if (closed) return null
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                if (closed) null else block()
            }
        }
    }

    sealed class OpenResult {
        data class Ok(val renderer: MuPdfPageRenderer) : OpenResult()
        object NeedsPassword : OpenResult()
        data class Error(val cause: Throwable? = null) : OpenResult()
    }

    companion object {

        /**
         * Open the document at [uri], optionally unlocking it with [password].
         * Runs on [Dispatchers.IO]. The caller must [close] the returned
         * renderer when done.
         */
        suspend fun open(
            context: Context,
            uri: Uri,
            password: String? = null,
        ): OpenResult = withContext(Dispatchers.IO) {
            val stream = try {
                PfdSeekableStream.open(context, uri)
            } catch (t: Throwable) {
                return@withContext OpenResult.Error(t)
            }
            val doc = try {
                Document.openDocument(stream, "application/pdf")
            } catch (t: Throwable) {
                stream.close()
                return@withContext OpenResult.Error(t)
            } ?: run {
                stream.close()
                return@withContext OpenResult.Error()
            }
            try {
                if (doc.needsPassword()) {
                    if (password.isNullOrEmpty()) {
                        doc.destroy()
                        stream.close()
                        return@withContext OpenResult.NeedsPassword
                    }
                    if (!doc.authenticatePassword(password)) {
                        doc.destroy()
                        stream.close()
                        return@withContext OpenResult.NeedsPassword
                    }
                }
                val pageCount = doc.countPages()
                if (pageCount <= 0) {
                    doc.destroy()
                    stream.close()
                    return@withContext OpenResult.Error()
                }
                OpenResult.Ok(MuPdfPageRenderer(stream, doc, pageCount))
            } catch (t: Throwable) {
                runCatching { doc.destroy() }
                runCatching { stream.close() }
                OpenResult.Error(t)
            }
        }

        private fun isExternalUri(uri: String): Boolean {
            if (uri.isBlank()) return false
            val lower = uri.lowercase()
            return lower.startsWith("http://") ||
                lower.startsWith("https://") ||
                lower.startsWith("mailto:") ||
                lower.startsWith("tel:") ||
                lower.startsWith("ftp://")
        }
    }
}
