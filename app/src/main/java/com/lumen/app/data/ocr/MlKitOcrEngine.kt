package com.lumen.app.data.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class MlKitOcrEngine @Inject constructor() : OcrEngine {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognize(bitmap: Bitmap): OcrResult =
        withTimeoutOrNull(20_000L) {
            suspendCancellableCoroutine { cont ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { result ->
                        if (!cont.isActive) return@addOnSuccessListener
                        val words = ArrayList<OcrWord>()
                        for (block in result.textBlocks) {
                            for (line in block.lines) {
                                for (element in line.elements) {
                                    val box = element.boundingBox ?: continue
                                    if (element.text.isNotBlank()) {
                                        words.add(OcrWord(element.text, box))
                                    }
                                }
                            }
                        }
                        cont.resume(OcrResult(result.text, words))
                    }
                    .addOnFailureListener { if (cont.isActive) cont.resume(OcrResult("")) }
            }
        } ?: OcrResult("")
}
