package com.lumen.app.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.util.SizeF
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Link
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Page
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import com.lumen.app.data.pdf.MuPdfGate
import com.lumen.app.data.pdf.PfdSeekableStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
        /** Pre-resolved 0-indexed page number for internal links; null for external or unresolvable. */
        val destPage: Int?,
    )

    /** One word of page text with its bounding box in page-pt coordinates
     *  (origin at the page's top-left, same space as [pageSize]). */
    data class WordBox(val text: String, val rect: RectF)

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
        // Gate the native render so only one rasterisation runs app-wide at a time.
        return MuPdfGate.withRenderPermit {
          withDocLock {
            val page = runCatching { doc.loadPage(index) }.getOrNull() ?: return@withDocLock null
            try {
                val matrix = Matrix(scale, scale)
                // AndroidDrawDevice rasterises natively into a white-cleared opaque
                // ARGB_8888 bitmap — stride, premultiplied alpha, and colorspace
                // conversion all happen inside fitz, so soft-masked content can
                // never bleed the canvas colour through.
                runCatching { AndroidDrawDevice.drawPage(page, matrix) }.getOrNull()
            } catch (_: Throwable) {
                null
            } finally {
                page.destroy()
            }
          }
        }
    }

    /**
     * Run [block] against the open document, holding the global render permit and
     * the document lock (in that order — the same order [renderPage] uses, so the
     * two paths can never deadlock). Lets callers reuse this session instead of
     * reopening the file per operation (highlight extraction previously reopened
     * and reparsed the whole PDF for every page it computed rects for).
     */
    suspend fun <R> withDocumentGated(block: (Document) -> R): R? =
        MuPdfGate.withRenderPermit { withDocLock { block(doc) } }

    /**
     * Words with boxes for [index], from MuPDF structured text. Used for
     * long-press word selection; scanned pages without a text layer return empty.
     * Gated like rendering — structured text allocates native memory.
     */
    suspend fun wordsForPage(index: Int): List<WordBox> {
        if (index !in 0 until pageCount) return emptyList()
        return MuPdfGate.withRenderPermit {
            withDocLock {
                val page = runCatching { doc.loadPage(index) }.getOrNull()
                    ?: return@withDocLock emptyList()
                try {
                    val bounds = page.bounds
                    val stext = runCatching { page.toStructuredText("preserve-whitespace") }
                        .getOrNull() ?: return@withDocLock emptyList<WordBox>()
                    try {
                        extractWords(stext, bounds.x0, bounds.y0)
                    } finally {
                        runCatching { stext.destroy() }
                    }
                } catch (_: Throwable) {
                    emptyList()
                } finally {
                    runCatching { page.destroy() }
                }
            }
        } ?: emptyList()
    }

    private fun extractWords(
        stext: com.artifex.mupdf.fitz.StructuredText,
        offsetX: Float,
        offsetY: Float,
    ): List<WordBox> {
        val words = ArrayList<WordBox>(256)
        val sb = StringBuilder()
        var minX = 0f; var minY = 0f; var maxX = 0f; var maxY = 0f
        var open = false

        fun flush() {
            if (open && sb.isNotEmpty()) {
                words.add(
                    WordBox(
                        text = sb.toString(),
                        rect = RectF(minX - offsetX, minY - offsetY, maxX - offsetX, maxY - offsetY),
                    )
                )
            }
            sb.setLength(0)
            open = false
        }

        val blocks = runCatching { stext.blocks }.getOrNull() ?: return emptyList()
        for (block in blocks) {
            val lines = runCatching { block?.lines }.getOrNull() ?: continue
            for (line in lines) {
                val chars = runCatching { line?.chars }.getOrNull() ?: continue
                for (ch in chars) {
                    val cp = ch?.c ?: continue
                    val quad = ch.quad ?: continue
                    if (Character.isWhitespace(cp)) {
                        flush()
                        continue
                    }
                    val qMinX = minOf(quad.ul_x, quad.ur_x, quad.ll_x, quad.lr_x)
                    val qMaxX = maxOf(quad.ul_x, quad.ur_x, quad.ll_x, quad.lr_x)
                    val qMinY = minOf(quad.ul_y, quad.ur_y, quad.ll_y, quad.lr_y)
                    val qMaxY = maxOf(quad.ul_y, quad.ur_y, quad.ll_y, quad.lr_y)
                    if (!open) {
                        minX = qMinX; minY = qMinY; maxX = qMaxX; maxY = qMaxY
                        open = true
                    } else {
                        if (qMinX < minX) minX = qMinX
                        if (qMinY < minY) minY = qMinY
                        if (qMaxX > maxX) maxX = qMaxX
                        if (qMaxY > maxY) maxY = qMaxY
                    }
                    sb.appendCodePoint(cp)
                }
                flush()
            }
        }
        return words
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
                    val external = isExternalUri(uri)
                    // Resolve internal link destinations while the page is still alive —
                    // MuPDF's Link references can become unsafe after page.destroy().
                    val destPage = if (!external) {
                        runCatching {
                            val loc = doc.resolveLink(link) ?: return@runCatching null
                            val n = doc.pageNumberFromLocation(loc)
                            if (n in 0 until pageCount) n else null
                        }.getOrNull()
                    } else null
                    LinkInfo(
                        bounds = RectF(bounds.x0, bounds.y0, bounds.x1, bounds.y1),
                        uri = uri,
                        isExternal = external,
                        destPage = destPage,
                    )
                }
            } finally {
                page.destroy()
            }
        } ?: emptyList()
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
