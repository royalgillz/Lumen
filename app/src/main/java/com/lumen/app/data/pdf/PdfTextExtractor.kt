package com.lumen.app.data.pdf

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PdfTextExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    enum class Outcome { OK, Encrypted, Error }

    /**
     * Opens the document once and calls [onPage] for each page (0-indexed).
     * Uses startPage/endPage so only one page is loaded into memory at a time —
     * never calls getText() on the whole document.
     */
    suspend fun extractAll(
        uri: Uri,
        onPage: suspend (pageIndex: Int, text: String) -> Unit,
    ): Outcome {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: return Outcome.Error
        return try {
            inputStream.use { stream ->
                PDDocument.load(stream).use { doc ->
                    val stripper = PDFTextStripper()
                    repeat(doc.numberOfPages) { i ->
                        stripper.startPage = i + 1
                        stripper.endPage = i + 1
                        val text = try { stripper.getText(doc) } catch (_: Exception) { "" }
                        onPage(i, text)
                    }
                }
            }
            Outcome.OK
        } catch (e: InvalidPasswordException) {
            Outcome.Encrypted
        } catch (e: Exception) {
            Outcome.Error
        }
    }

    fun pageCount(uri: Uri): Int? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                PDDocument.load(stream).use { it.numberOfPages }
            }
        } catch (e: Exception) {
            null
        }
    }
}
