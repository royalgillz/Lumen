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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

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
    private val closeStarted = AtomicBoolean(false)
    /** Owns the deferred native teardown in [close]; never cancelled — the destroy must run. */
    private val closeScope = CoroutineScope(Dispatchers.IO)

    data class LinkInfo(
        val bounds: RectF,
        val uri: String,
        val isExternal: Boolean,
        /** Pre-resolved 0-indexed page number for internal links; null for external or unresolvable. */
        val destPage: Int?,
    )

    /** One word of page text with its bounding box in page-pt coordinates
     *  (origin at the page's top-left, same space as [pageSize]). [line] is the
     *  word's reading-order line index within the page (structured-text line
     *  order), so multi-word selection can union boxes and break copied text
     *  per source line. */
    data class WordBox(val text: String, val rect: RectF, val line: Int = 0)

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
     * Render a sub-rect of page [index] at full [scale] — the sharp path for
     * oversized pages whose whole-page bitmap the caps would blur. The region
     * is in page-local points (origin at the page bounds' top-left, the same
     * space as [pageSize] and [WordBox.rect]). Renders patch-style: the ctm
     * maps the whole page to device pixels and the draw device's origin is
     * offset to the region, so only the region rasterises into the bitmap.
     */
    // @spec VIEW-BIG-002
    suspend fun renderRegion(
        index: Int,
        scale: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): Bitmap? {
        if (index !in 0 until pageCount) return null
        if (scale <= 0f || right <= left || bottom <= top) return null
        val w = ((right - left) * scale).toInt()
        val h = ((bottom - top) * scale).toInt()
        if (w < 1 || h < 1) return null
        return MuPdfGate.withRenderPermit {
            withDocLock {
                val page = runCatching { doc.loadPage(index) }.getOrNull() ?: return@withDocLock null
                try {
                    val b: Rect = page.bounds
                    // Page pt → device px with the page origin at (0,0), matching
                    // the page-local space every caller uses.
                    val ctm = Matrix(scale, 0f, 0f, scale, -b.x0 * scale, -b.y0 * scale)
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    val dev = AndroidDrawDevice(bitmap, (left * scale).toInt(), (top * scale).toInt())
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
        var lineIndex = 0

        fun flush() {
            if (open && sb.isNotEmpty()) {
                words.add(
                    WordBox(
                        text = sb.toString(),
                        rect = RectF(minX - offsetX, minY - offsetY, maxX - offsetX, maxY - offsetY),
                        line = lineIndex,
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
                lineIndex++
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

    /**
     * Idempotent and non-blocking, safe from the main thread. Flips [closed]
     * first so no new operation starts, then destroys the native document under
     * the [mutex] on a background coroutine — destroying concurrently with an
     * in-flight native render would free the fz_document under it (SIGSEGV),
     * and waiting for the mutex on the caller would block the main thread
     * behind a long render.
     */
    override fun close() {
        if (!closeStarted.compareAndSet(false, true)) return
        closed = true
        closeScope.launch(NonCancellable) {
            mutex.withLock {
                runCatching { doc.destroy() }
                runCatching { stream.close() }
            }
        }
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
         * renderer when done — on EVERY path: the block runs [NonCancellable]
         * (it has no suspension points anyway), so a caller cancelled mid-open
         * still receives the result instead of a thrown CancellationException
         * silently leaking the open fd + native document; the caller checks its
         * own liveness and closes the renderer rather than adopting it.
         */
        suspend fun open(
            context: Context,
            uri: Uri,
            password: String? = null,
        ): OpenResult = withContext(Dispatchers.IO + NonCancellable) {
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
