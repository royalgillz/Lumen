package com.lumen.app.data.ocr

import android.graphics.Bitmap
import android.graphics.Rect

interface OcrEngine {
    /**
     * Recognize text in [bitmap], returning the full text plus per-word bounding
     * boxes (in the bitmap's pixel space). Boxes let the viewer highlight matches on
     * scanned pages, which have no PDF text layer for MuPDF to locate.
     */
    suspend fun recognize(bitmap: Bitmap): OcrResult
}

/** A recognized word and its bounding box, in the source bitmap's pixel coordinates. */
data class OcrWord(val text: String, val box: Rect)

data class OcrResult(val text: String, val words: List<OcrWord> = emptyList())
