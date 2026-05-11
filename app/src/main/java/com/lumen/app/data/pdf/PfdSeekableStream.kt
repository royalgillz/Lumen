package com.lumen.app.data.pdf

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.SeekableInputStream
import java.io.FileInputStream
import java.io.IOException

/**
 * Adapts a [ParcelFileDescriptor] obtained from SAF to MuPDF's [SeekableInputStream].
 *
 * MuPDF opens the document lazily and seeks around to read objects on demand, so we
 * need real seek support rather than buffering the whole file into memory (which
 * could be hundreds of MB for OCR scans).
 *
 * The wrapper takes ownership of the [ParcelFileDescriptor]; [close] releases the
 * underlying fd. Callers must keep the stream alive for the lifetime of the
 * MuPDF [Document].
 */
class PfdSeekableStream private constructor(
    private val pfd: ParcelFileDescriptor,
    private val fis: FileInputStream,
) : SeekableInputStream, AutoCloseable {

    private val channel = fis.channel
    private var closed = false

    override fun seek(offset: Long, whence: Int): Long {
        val newPos = when (whence) {
            SEEK_SET -> offset
            SEEK_CUR -> channel.position() + offset
            SEEK_END -> channel.size() + offset
            else -> throw IOException("Unknown whence: $whence")
        }
        if (newPos < 0) throw IOException("Negative seek position: $newPos")
        channel.position(newPos)
        return newPos
    }

    override fun position(): Long = channel.position()

    override fun read(buf: ByteArray): Int {
        val n = fis.read(buf)
        // MuPDF expects 0 (not -1) at EOF.
        return if (n < 0) 0 else n
    }

    override fun close() {
        if (closed) return
        closed = true
        // FileInputStream.close() releases the underlying fd; PFD.close() then no-ops.
        runCatching { fis.close() }
        runCatching { pfd.close() }
    }

    companion object {
        private const val SEEK_SET = 0
        private const val SEEK_CUR = 1
        private const val SEEK_END = 2

        @Throws(IOException::class)
        fun open(context: Context, uri: Uri): PfdSeekableStream {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Cannot open URI: $uri")
            val fis = FileInputStream(pfd.fileDescriptor)
            return PfdSeekableStream(pfd, fis)
        }
    }
}

/**
 * Opens a MuPDF [Document] from a SAF [Uri], runs [block], then guarantees the
 * document and its backing fd are closed. Returns null on any failure.
 *
 * Suitable for short-lived one-shot tasks (OCR page render, thumbnail, highlight
 * rect extraction). For the viewer session, hold the document open across many
 * page renders.
 *
 * The backing [PfdSeekableStream] must outlive the [Document] — MuPDF reads from
 * the stream lazily on every page load, so closing the stream too early causes
 * later loadPage() calls to fail.
 */
inline fun <R> withMuPdfDocument(
    context: Context,
    uri: Uri,
    password: String? = null,
    block: (Document) -> R,
): R? {
    val stream = try {
        PfdSeekableStream.open(context, uri)
    } catch (_: Exception) {
        return null
    }
    val doc: Document = try {
        Document.openDocument(stream, "application/pdf")
    } catch (_: Throwable) {
        stream.close()
        return null
    } ?: run {
        stream.close()
        return null
    }
    try {
        if (doc.needsPassword()) {
            if (password.isNullOrEmpty()) return null
            if (!doc.authenticatePassword(password)) return null
        }
        return block(doc)
    } catch (_: Throwable) {
        return null
    } finally {
        runCatching { doc.destroy() }
        runCatching { stream.close() }
    }
}
