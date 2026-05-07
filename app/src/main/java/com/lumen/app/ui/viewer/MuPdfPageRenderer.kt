package com.lumen.app.ui.viewer

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.DrawDevice
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Pixmap
import com.artifex.mupdf.fitz.SeekableInputStream
import com.artifex.mupdf.fitz.SeekableStream
import java.io.FileInputStream
import java.io.IOException
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.roundToInt

data class MuLink(
    val bounds: RectF,
    val uri: String?,
    val pageTarget: Int?,
)

class MuPdfPageRenderer {
    private var document: Document? = null
    private var activeStream: PfdSeekableInputStream? = null
    /** Must stay open until [close]; closing early breaks MuPDF's seekable stream. */
    private var heldPfd: ParcelFileDescriptor? = null

    fun open(pfd: ParcelFileDescriptor, password: String? = null) {
        close()
        heldPfd = pfd
        val stream = PfdSeekableInputStream(pfd)
        val doc = try {
            Document.openDocument(stream, "application/pdf")
        } catch (t: Throwable) {
            stream.close()
            throw t
        }
        if (doc.needsPassword()) {
            val isValidPassword = !password.isNullOrBlank() && doc.authenticatePassword(password)
            if (!isValidPassword) {
                doc.destroy()
                stream.close()
                throw SecurityException("Password required or incorrect.")
            }
        }
        activeStream = stream
        document = doc
    }

    fun pageCount(): Int = document?.countPages() ?: 0

    fun renderPage(index: Int, widthPx: Int): Bitmap {
        val doc = requireNotNull(document) { "Document is not open." }
        val page = doc.loadPage(index)
        try {
            val bounds = page.bounds
            val pageWidthPts = (bounds.x1 - bounds.x0).coerceAtLeast(1f)
            val pageHeightPts = (bounds.y1 - bounds.y0).coerceAtLeast(1f)
            val scale = max(widthPx, 1).toFloat() / pageWidthPts
            val bitmapWidth = max(widthPx, 1)
            val bitmapHeight = (pageHeightPts * scale).roundToInt().coerceAtLeast(1)
            // DeviceBGR + alpha gives BGRA samples; getPixels() matches Android ARGB_8888 ints.
            // (DeviceRGB pixmap pixels were RGBA and were previously misread as ARGB → swapped R/B.)
            val pixmap = Pixmap(ColorSpace.DeviceBGR, bitmapWidth, bitmapHeight, true)
            try {
                pixmap.clear(0xFFFFFFFF.toInt())
                val matrix = Matrix(scale, 0f, 0f, scale, -bounds.x0 * scale, -bounds.y0 * scale)
                val device = DrawDevice(pixmap)
                try {
                    page.run(device, matrix, null)
                } finally {
                    device.close()
                    device.destroy()
                }
                val pixels = pixmap.pixels
                return Bitmap.createBitmap(pixels, bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
            } finally {
                pixmap.destroy()
            }
        } finally {
            page.destroy()
        }
    }

    fun getLinks(index: Int): List<MuLink> {
        val doc = requireNotNull(document) { "Document is not open." }
        val page = doc.loadPage(index)
        try {
            val links = page.links ?: return emptyList()
            return links.map { link ->
                val bounds = link.bounds
                val uri = link.uri
                val resolvedPage = runCatching {
                    val location = doc.resolveLink(link)
                    doc.pageNumberFromLocation(location)
                }.getOrNull()
                MuLink(
                    bounds = RectF(bounds.x0, bounds.y0, bounds.x1, bounds.y1),
                    uri = uri,
                    pageTarget = parsePageTarget(uri) ?: resolvedPage,
                )
            }
        } finally {
            page.destroy()
        }
    }

    fun pageSize(index: Int): PointF {
        val doc = requireNotNull(document) { "Document is not open." }
        val page = doc.loadPage(index)
        try {
            val bounds = page.bounds
            return PointF(bounds.x1 - bounds.x0, bounds.y1 - bounds.y0)
        } finally {
            page.destroy()
        }
    }

    fun close() {
        document?.destroy()
        document = null
        activeStream?.close()
        activeStream = null
        runCatching { heldPfd?.close() }
        heldPfd = null
    }

    private fun parsePageTarget(uri: String?): Int? {
        if (uri.isNullOrBlank()) return null
        val marker = "#page="
        val markerIndex = uri.indexOf(marker, ignoreCase = true)
        if (markerIndex < 0) return null
        val raw = uri.substring(markerIndex + marker.length)
            .takeWhile { it.isDigit() }
        val pageOneBased = raw.toIntOrNull() ?: return null
        return (pageOneBased - 1).coerceAtLeast(0)
    }

    private class PfdSeekableInputStream(
        pfd: ParcelFileDescriptor,
    ) : SeekableInputStream {
        private val input = FileInputStream(pfd.fileDescriptor)
        private val channel: FileChannel = input.channel

        override fun read(buffer: ByteArray): Int = input.read(buffer)

        override fun seek(offset: Long, whence: Int): Long {
            val target = when (whence) {
                SeekableStream.SEEK_SET -> offset
                SeekableStream.SEEK_CUR -> channel.position() + offset
                SeekableStream.SEEK_END -> channel.size() + offset
                else -> throw IOException("Unknown seek mode: $whence")
            }.coerceAtLeast(0L)
            channel.position(target)
            return target
        }

        override fun position(): Long = channel.position()

        fun close() {
            channel.close()
            input.close()
        }
    }
}
