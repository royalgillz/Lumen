package com.lumen.app.data.ocr

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TesseractOcrEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : OcrEngine {

    private val tessDataParent: File get() = context.filesDir
    private val trainedDataFile: File get() = File(tessDataParent, "tessdata/eng.traineddata")

    init {
        copyTessDataIfNeeded()
    }

    // Copies eng.traineddata from assets on first run if bundled; silent no-op otherwise.
    // Place the file at app/src/main/assets/tessdata/eng.traineddata to enable Tesseract.
    private fun copyTessDataIfNeeded() {
        if (trainedDataFile.exists()) return
        try {
            trainedDataFile.parentFile?.mkdirs()
            context.assets.open("tessdata/eng.traineddata").use { src ->
                trainedDataFile.outputStream().use { dst -> src.copyTo(dst) }
            }
        } catch (_: Exception) {
            // Asset not bundled, Tesseract will silently skip on each call
        }
    }

    override suspend fun recognize(bitmap: Bitmap): OcrResult =
        withTimeoutOrNull(30_000L) {
            withContext(Dispatchers.IO) {
                if (!trainedDataFile.exists()) return@withContext OcrResult("")
                val api = TessBaseAPI()
                try {
                    if (!api.init(tessDataParent.absolutePath, "eng")) return@withContext OcrResult("")
                    api.setImage(bitmap)
                    // Tesseract is only the fallback when ML Kit yields too little text;
                    // we keep the text but skip word boxes here to stay simple.
                    OcrResult(api.getUTF8Text() ?: "")
                } catch (e: Exception) {
                    OcrResult("")
                } finally {
                    api.recycle()
                }
            }
        } ?: OcrResult("")
}
