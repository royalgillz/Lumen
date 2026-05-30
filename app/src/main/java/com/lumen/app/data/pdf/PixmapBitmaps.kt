package com.lumen.app.data.pdf

import android.graphics.Bitmap
import com.artifex.mupdf.fitz.Pixmap
import java.nio.ByteBuffer

/**
 * Convert a MuPDF [Pixmap] to an ARGB_8888 [Bitmap].
 *
 * Pages are rendered over an opaque white backdrop (`alpha = false`) so PDF
 * transparency and soft masks composite the way reference viewers do — rendering
 * onto a transparent backdrop makes some soft-masked icons collapse into solid
 * colour blocks. That produces a 3-component (RGB) pixmap, which this expands to
 * RGBA for the bitmap. A 4-component pixmap is copied straight through.
 *
 * Returns null on any size/format mismatch (the bitmap is recycled first).
 */
fun pixmapToBitmap(pixmap: Pixmap): Bitmap? {
    val w = pixmap.width
    val h = pixmap.height
    if (w <= 0 || h <= 0) return null
    val samples = pixmap.samples ?: return null
    val pixels = w * h
    if (pixels <= 0) return null
    // fitz pixmaps are tightly packed, so bytes-per-pixel falls out of the buffer size.
    val bytesPerPixel = samples.size / pixels
    val bmp = try {
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    } catch (_: OutOfMemoryError) {
        return null
    }
    return when (bytesPerPixel) {
        4 -> {
            val expected = pixels * 4
            if (samples.size < expected) {
                bmp.recycle(); null
            } else {
                bmp.copyPixelsFromBuffer(ByteBuffer.wrap(samples, 0, expected))
                bmp
            }
        }
        3 -> {
            val expected = pixels * 3
            if (samples.size < expected) {
                bmp.recycle(); null
            } else {
                val rgba = ByteArray(pixels * 4)
                var s = 0
                var d = 0
                val opaque = 0xFF.toByte()
                repeat(pixels) {
                    rgba[d] = samples[s]
                    rgba[d + 1] = samples[s + 1]
                    rgba[d + 2] = samples[s + 2]
                    rgba[d + 3] = opaque
                    s += 3
                    d += 4
                }
                bmp.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
                bmp
            }
        }
        else -> {
            bmp.recycle(); null
        }
    }
}
