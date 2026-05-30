package com.lumen.app.data.pdf

import android.graphics.Bitmap
import com.artifex.mupdf.fitz.Pixmap
import java.nio.ByteBuffer

/**
 * Convert a MuPDF [Pixmap] to an ARGB_8888 [Bitmap].
 *
 * Pages are rendered WITH alpha (`alpha = true`) so MuPDF keeps soft-mask
 * transparency on images instead of flattening them onto their own backdrop
 * colour (the "solid teal block" symptom). This converter then composites the
 * resulting RGBA over opaque white, the way reference viewers do. A 3-component
 * (RGB) pixmap, if ever produced, is treated as already-opaque.
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
                // Composite over opaque white rather than copying the RGBA straight
                // through: a transparent pixmap region would otherwise let the dark
                // canvas show through (the "green/teal block" symptom on soft-masked
                // content). Forcing white matches reference viewers.
                val out = ByteArray(expected)
                var i = 0
                while (i < expected) {
                    val a = samples[i + 3].toInt() and 0xFF
                    if (a == 255) {
                        out[i] = samples[i]
                        out[i + 1] = samples[i + 1]
                        out[i + 2] = samples[i + 2]
                    } else {
                        val inv = 255 - a
                        out[i] = (((samples[i].toInt() and 0xFF) * a + 255 * inv) / 255).toByte()
                        out[i + 1] = (((samples[i + 1].toInt() and 0xFF) * a + 255 * inv) / 255).toByte()
                        out[i + 2] = (((samples[i + 2].toInt() and 0xFF) * a + 255 * inv) / 255).toByte()
                    }
                    out[i + 3] = 0xFF.toByte()
                    i += 4
                }
                bmp.copyPixelsFromBuffer(ByteBuffer.wrap(out))
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
