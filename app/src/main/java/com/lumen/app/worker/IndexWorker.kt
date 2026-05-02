package com.lumen.app.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lumen.app.R
import com.lumen.app.data.db.dao.DocumentDao
import com.lumen.app.data.db.dao.LineDao
import com.lumen.app.data.db.dao.PageDao
import com.lumen.app.data.db.entity.DocumentEntity
import com.lumen.app.data.db.entity.LineContentEntity
import com.lumen.app.data.db.entity.PageEntity
import com.lumen.app.data.fs.PdfFile
import com.lumen.app.data.fs.PdfScanner
import com.lumen.app.data.ocr.MlKitOcrEngine
import com.lumen.app.data.ocr.TesseractOcrEngine
import com.lumen.app.data.pdf.LineExtractor
import com.lumen.app.data.pdf.PdfPageRenderer
import com.lumen.app.data.pdf.PdfTextExtractor
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltWorker
class IndexWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val pdfScanner: PdfScanner,
    private val pdfTextExtractor: PdfTextExtractor,
    private val pdfPageRenderer: PdfPageRenderer,
    private val lineExtractor: LineExtractor,
    private val mlKitOcrEngine: MlKitOcrEngine,
    private val tesseractOcrEngine: TesseractOcrEngine,
    private val documentDao: DocumentDao,
    private val pageDao: PageDao,
    private val lineDao: LineDao,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val folderUri = inputData.getString(KEY_FOLDER_URI)
            ?.let { Uri.parse(it) }
            ?: return@withContext Result.failure()

        setForeground(buildForegroundInfo("Scanning for PDFs…"))

        val pdfs = try {
            pdfScanner.scanTree(folderUri)
        } catch (e: Exception) {
            return@withContext Result.failure()
        }

        if (pdfs.isEmpty()) return@withContext Result.success()

        pdfs.forEachIndexed { index, pdf ->
            setProgress(workDataOf(KEY_PROGRESS to index, KEY_TOTAL to pdfs.size))
            setForeground(buildForegroundInfo("${index + 1} / ${pdfs.size}: ${pdf.filename}"))
            indexPdf(pdf)
        }

        Result.success()
    }

    private suspend fun indexPdf(pdf: PdfFile) {
        val uriStr = pdf.uri.toString()

        val existing = documentDao.getByUri(uriStr)
        if (existing != null
            && existing.status == DocumentEntity.STATUS_INDEXED
            && existing.lastModified == pdf.lastModified) {
            return
        }

        val docId = documentDao.upsert(
            DocumentEntity(
                id = existing?.id ?: 0,
                uri = uriStr,
                filename = pdf.filename,
                status = DocumentEntity.STATUS_INDEXING,
                lastModified = pdf.lastModified,
                sizeBytes = pdf.sizeBytes,
                addedAt = existing?.addedAt ?: System.currentTimeMillis(),
            )
        )

        // Foreign key CASCADE on PageEntity deletes all lines too
        pageDao.deleteByDocument(docId)

        var pageCount = 0
        val outcome = pdfTextExtractor.extractAll(pdf.uri) { pageIndex, rawText ->
            val needsOcr = rawText.trim().length < MIN_CHARS_TEXT_PDF
            val finalText = if (needsOcr) ocrPage(pdf.uri, pageIndex) ?: rawText else rawText
            val usedOcr = needsOcr && finalText.trim().isNotEmpty()

            val pageId = pageDao.insert(
                PageEntity(docId = docId, pageNumber = pageIndex, isOcr = usedOcr)
            )
            val lines = lineExtractor.extract(finalText).mapIndexed { i, lineText ->
                LineContentEntity(pageId = pageId, lineNumber = i, text = lineText)
            }
            if (lines.isNotEmpty()) lineDao.insertAll(lines)
            pageCount++
        }

        when (outcome) {
            PdfTextExtractor.Outcome.OK ->
                documentDao.markIndexed(
                    id = docId,
                    status = DocumentEntity.STATUS_INDEXED,
                    pageCount = pageCount,
                    indexedAt = System.currentTimeMillis(),
                )
            PdfTextExtractor.Outcome.Encrypted ->
                documentDao.updateStatus(docId, DocumentEntity.STATUS_ENCRYPTED)
            PdfTextExtractor.Outcome.Error ->
                documentDao.updateStatus(docId, DocumentEntity.STATUS_ERROR)
        }
    }

    /** Renders [pageIndex] and runs ML Kit → Tesseract fallback. Recycles bitmap after use. */
    private suspend fun ocrPage(uri: Uri, pageIndex: Int): String? {
        val bitmap = pdfPageRenderer.renderPage(uri, pageIndex) ?: return null
        return try {
            val mlKitText = mlKitOcrEngine.recognizeText(bitmap)
            if (mlKitText.length >= MIN_OCR_CHARS) {
                mlKitText
            } else {
                val tessText = tesseractOcrEngine.recognizeText(bitmap)
                if (tessText.length > mlKitText.length) tessText else mlKitText
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun buildForegroundInfo(text: String): ForegroundInfo {
        ensureNotificationChannel()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Indexing PDFs")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "PDF Indexing",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = "Shows progress while Lumen indexes your PDFs" }
                )
            }
        }
    }

    companion object {
        const val KEY_FOLDER_URI = "folder_uri"
        const val KEY_PROGRESS = "progress"
        const val KEY_TOTAL = "total"
        private const val CHANNEL_ID = "lumen_indexing"
        private const val NOTIFICATION_ID = 1001

        // Pages yielding fewer chars from PdfBox are treated as image/scanned pages
        private const val MIN_CHARS_TEXT_PDF = 50
        // ML Kit result must have at least this many chars to skip Tesseract fallback
        private const val MIN_OCR_CHARS = 5

        fun buildRequest(folderUri: Uri): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<IndexWorker>()
                .setInputData(workDataOf(KEY_FOLDER_URI to folderUri.toString()))
                .addTag("index")
                .build()
    }
}
