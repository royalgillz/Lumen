package com.lumen.app.data.ocr

import android.graphics.Bitmap

interface OcrEngine {
    suspend fun recognizeText(bitmap: Bitmap): String
}
