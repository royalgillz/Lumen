package com.lumen.app.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
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
import com.lumen.app.data.db.dao.PageDao
import com.lumen.app.data.db.dao.PageTextDao
import com.lumen.app.data.db.entity.DocumentEntity
import com.lumen.app.data.db.entity.PageEntity
import com.lumen.app.data.db.entity.PageTextEntity
import com.lumen.app.data.db.entity.mergeForReindex
import com.lumen.app.domain.model.DocumentTitles
import androidx.room.withTransaction
import com.lumen.app.data.db.LumenDatabase
import com.lumen.app.data.fs.PdfFile
import com.lumen.app.data.fs.PdfScanner
import com.lumen.app.data.fs.caseRenameTempTarget
import com.lumen.app.data.ocr.MlKitOcrEngine
import com.lumen.app.data.ocr.OcrStrips
import com.lumen.app.data.ocr.OcrWordBoxes
import com.lumen.app.data.ocr.TesseractOcrEngine
import com.lumen.app.data.pdf.PdfPageRenderer
import com.lumen.app.data.pdf.PdfTextExtractor
import com.lumen.app.data.text.TextNormalizer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@HiltWorker
class IndexWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val pdfScanner: PdfScanner,
    private val pdfTextExtractor: PdfTextExtractor,
    private val pdfPageRenderer: PdfPageRenderer,
    private val mlKitOcrEngine: MlKitOcrEngine,
    private val tesseractOcrEngine: TesseractOcrEngine,
    private val database: LumenDatabase,
    private val documentDao: DocumentDao,
    private val pageDao: PageDao,
    private val pageTextDao: PageTextDao,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Single-document ephemeral mode: an external VIEW-intent doc whose
        // grant persists gets indexed alone, under a rolling TTL. No folder is
        // involved, so none of the folder machinery below may run.
        // @spec LIB-EXT-014
        inputData.getString(KEY_EXTERNAL_DOC_URI)?.let { externalUri ->
            return@withContext indexExternalDocument(Uri.parse(externalUri))
        }

        val folderUri = inputData.getString(KEY_FOLDER_URI)
            ?.let { Uri.parse(it) }
            ?: return@withContext Result.failure()
        val force = inputData.getBoolean(KEY_FORCE, false)

        // Every normal folder pass starts by sweeping expired ephemeral docs —
        // a cheap no-op when nothing expired, and it keeps TTL enforcement
        // from depending on any single trigger point.
        // @spec LIB-EXT-015
        documentDao.purgeExpiredEphemeral(System.currentTimeMillis())

        setForeground(buildForegroundInfo("Scanning for PDFs…"))

        val scanned = try {
            pdfScanner.scanTree(folderUri)
        } catch (e: Exception) {
            return@withContext Result.failure()
        }
        // @spec LIB-REN-015 — finish interrupted two-step case renames first, so
        // the loop and the vanished-cleanup `seen` set hold the final URIs.
        val pdfs = scanned.map { recoverInterruptedCaseRename(it) }

        if (pdfs.isEmpty()) {
            // Folder whose PDFs were all deleted must empty out of the index too
            removeVanishedDocuments(folderUri, emptySet())
            return@withContext Result.success()
        }

        pdfs.forEachIndexed { index, pdf ->
            setProgress(workDataOf(KEY_PROGRESS to index, KEY_TOTAL to pdfs.size))
            setForeground(buildForegroundInfo("${index + 1} / ${pdfs.size}: ${pdf.filename}"))
            val completed = try {
                withTimeoutOrNull(indexTimeoutMs(pdf.sizeBytes)) {
                    indexPdf(pdf, treeUri = folderUri.toString(), force = force)
                } != null
            } catch (_: Exception) {
                false
            }
            if (!completed) {
                documentDao.getByUri(pdf.uri.toString())?.id?.let { docId ->
                    documentDao.updateStatus(docId, DocumentEntity.STATUS_ERROR)
                }
            }
        }

        val seen = pdfs.map { it.uri.toString() }.toSet()
        removeVanishedDocuments(folderUri, seen)

        Result.success()
    }

    /**
     * A file wearing the reserved case-rename temp suffix is a two-step rename
     * (LIB-REN-013) that died between steps: finish it so the name the user
     * chose — not the temp name — is what gets indexed. Runs before the index
     * loop so the vanished-document cleanup's `seen` set holds the final URIs.
     * On any failure (collision, provider refusal) the file indexes as-is.
     */
    // @spec LIB-REN-015
    private fun recoverInterruptedCaseRename(pdf: PdfFile): PdfFile {
        val target = caseRenameTempTarget(pdf.filename) ?: return pdf
        return runCatching {
            val newUri = DocumentsContract.renameDocument(context.contentResolver, pdf.uri, target)
                ?: return pdf
            pdf.copy(uri = newUri, filename = target)
        }.getOrDefault(pdf)
    }

    // Drop documents whose files no longer exist in the folder; the FK cascade
    // cleans up pages/page_text rows. Structurally unable to touch ephemeral
    // docs: idUrisByTreeUri filters `ephemeralExpiresAt IS NULL` (and ephemeral
    // rows carry treeUri = '' besides) — only the TTL purge may delete them.
    // @spec LIB-EXT-003
    private suspend fun removeVanishedDocuments(folderUri: Uri, seen: Set<String>) {
        documentDao.idUrisByTreeUri(folderUri.toString()).forEach { row ->
            if (row.uri !in seen) documentDao.delete(row.id)
        }
    }

    /**
     * Ephemeral mode: index exactly one externally-opened document under a
     * rolling 7-day TTL. treeUri stays the '' sentinel so no folder-scoped
     * query can ever adopt or delete the row, and the vanished-document cleanup
     * never runs here (there is no folder whose contents could vanish).
     *
     * A permanent library row at the same URI is left untouched — stamping it
     * would demote a library document into the purge's reach. The TTL is
     * restamped explicitly after the pass because the re-index entity merge
     * deliberately carries an existing row's old expiry (LIB-EXT-003).
     */
    // @spec LIB-EXT-014
    private suspend fun indexExternalDocument(docUri: Uri): Result {
        val uriStr = docUri.toString()
        val existing = documentDao.getByUri(uriStr)
        if (existing != null && existing.ephemeralExpiresAt == null) {
            // Already a permanent library citizen; the folder machinery owns it.
            return Result.success()
        }

        // Access died between enqueue and run (or the provider is gone):
        // nothing was written, nothing to clean up.
        val pdf = queryExternalPdf(docUri) ?: return Result.failure()
        setForeground(buildForegroundInfo(pdf.filename))

        val expiresAt = System.currentTimeMillis() + EPHEMERAL_TTL_MS
        // Restamp an existing row UP FRONT: the re-index merge deliberately
        // carries the row's OLD expiry for the whole multi-minute pass, and a
        // concurrent purge trigger (every folder pass runs one) must never
        // reap the document the user just opened mid-index. Conditional in the
        // DAO — a permanent row cannot be stamped.
        // @spec LIB-EXT-004, LIB-EXT-014
        if (existing != null) documentDao.setEphemeralExpiry(uriStr, expiresAt)
        val completed = try {
            withTimeoutOrNull(indexTimeoutMs(pdf.sizeBytes)) {
                indexPdf(pdf, treeUri = "", force = false, ephemeralExpiresAt = expiresAt)
            } != null
        } catch (_: Exception) {
            false
        }
        if (!completed) {
            documentDao.getByUri(uriStr)?.id?.let { docId ->
                documentDao.updateStatus(docId, DocumentEntity.STATUS_ERROR)
            }
        }
        // Rolling TTL: this pass exists because the document was just opened,
        // so whatever row survives it (fresh, merged, or skip-because-unchanged)
        // gets the full window. The DAO's ephemeral-only condition keeps a
        // permanent row from ever being demoted.
        // @spec LIB-EXT-004, LIB-EXT-014
        documentDao.setEphemeralExpiry(uriStr, expiresAt)
        return Result.success()
    }

    /** Metadata for a single external document, queried directly (no tree to
     *  scan). Null when the resolver can't see the document any more. */
    private fun queryExternalPdf(docUri: Uri): PdfFile? = runCatching {
        context.contentResolver.query(
            docUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE,
            ),
            null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val modCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val sizeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            PdfFile(
                uri = docUri,
                filename = c.stringOrNull(nameCol)
                    ?: inputData.getString(KEY_EXTERNAL_DISPLAY_NAME)
                    ?: "PDF",
                // 0 when the provider omits the column — sameFileContents
                // already falls back to size equality for exactly that case.
                lastModified = c.longOrZero(modCol),
                sizeBytes = c.longOrZero(sizeCol),
            )
        }
    }.getOrNull()

    private fun android.database.Cursor.stringOrNull(col: Int): String? =
        if (col >= 0 && !isNull(col)) getString(col)?.takeIf { it.isNotBlank() } else null

    private fun android.database.Cursor.longOrZero(col: Int): Long =
        if (col >= 0 && !isNull(col)) getLong(col) else 0L

    private suspend fun indexPdf(
        pdf: PdfFile,
        treeUri: String,
        force: Boolean,
        ephemeralExpiresAt: Long? = null,
    ) {
        val uriStr = pdf.uri.toString()

        val existing = documentDao.getByUri(uriStr)
        // @spec LIB-IDX-003
        if (!force
            && existing != null
            && existing.status == DocumentEntity.STATUS_INDEXED
            && sameFileContents(
                existingLastModified = existing.lastModified,
                existingSize = existing.sizeBytes,
                scannedLastModified = pdf.lastModified,
                scannedSize = pdf.sizeBytes,
            )) {
            return
        }

        // REPLACE would wipe any column not threaded through; the merge preserves
        // identity and user recency while this pass owns the extraction columns.
        // @spec LIB-TTL-009
        val docId = documentDao.upsert(
            mergeForReindex(
                existing,
                DocumentEntity(
                    uri = uriStr,
                    filename = pdf.filename,
                    treeUri = treeUri,
                    status = DocumentEntity.STATUS_INDEXING,
                    lastModified = pdf.lastModified,
                    sizeBytes = pdf.sizeBytes,
                    // Only seeds a FRESH row (the merge carries an existing
                    // row's own expiry): ephemeral from birth, so no library
                    // surface ever glimpses it mid-index.
                    // @spec LIB-EXT-014
                    ephemeralExpiresAt = ephemeralExpiresAt,
                ),
            )
        )
        pageDao.deleteByDocument(docId)

        // Pass 1: extract text from every page via PdfBox
        data class PageData(val index: Int, val text: String, val needsOcr: Boolean)
        val pages = mutableListOf<PageData>()
        var metadataTitle: String? = null
        var metadataAuthor: String? = null
        val outcome = pdfTextExtractor.extractAll(
            pdf.uri,
            onMetadata = { title, author ->
                metadataTitle = title
                metadataAuthor = author
            },
        ) { pageIndex, rawText ->
            val needsOcr = rawText.trim().length < MIN_CHARS_TEXT_PDF
            pages.add(PageData(pageIndex, rawText, needsOcr))
        }

        // Pass 2: OCR, open PdfRenderer once for all pages that need it.
        // Normal pages arrive whole; extreme-aspect pages arrive as strips
        // (rendering them whole allocates in the hundred-MB class and fails) —
        // strip words are kept by the band owning their vertical center (the
        // seam rule dedupes the render overlap) and re-based to full-page
        // pixels so stored boxes stay page-normalised for viewer highlights.
        // @spec LIB-BIG-001
        val ocrTexts = mutableMapOf<Int, String>()
        val ocrBoxes = mutableMapOf<Int, String?>()
        val ocrPageIndices = pages.filter { it.needsOcr }.map { it.index }
        if (ocrPageIndices.isNotEmpty()) {
            suspend fun recognizeWithFallback(bitmap: android.graphics.Bitmap): com.lumen.app.data.ocr.OcrResult {
                val ml = mlKitOcrEngine.recognize(bitmap)
                if (ml.text.length >= MIN_OCR_CHARS) return ml
                val tess = tesseractOcrEngine.recognize(bitmap)
                return if (tess.text.length > ml.text.length) tess else ml
            }

            val stripWords = mutableListOf<com.lumen.app.data.ocr.OcrWord>()
            pdfPageRenderer.renderPagesInSession(pdf.uri, ocrPageIndices) { pageIndex, piece ->
                try {
                    when (piece) {
                        is PdfPageRenderer.OcrPiece.WholePage -> {
                            val chosen = recognizeWithFallback(piece.bitmap)
                            ocrTexts[pageIndex] = chosen.text
                            ocrBoxes[pageIndex] =
                                OcrWordBoxes.encode(chosen.words, piece.bitmap.width, piece.bitmap.height)
                        }
                        is PdfPageRenderer.OcrPiece.Strip -> {
                            // A fresh page's first strip clears any residue a
                            // previous page's failed final strip left behind.
                            if (piece.band.bandTopPt == 0f) stripWords.clear()
                            val chosen = recognizeWithFallback(piece.bitmap)
                            val offsetYPx = piece.band.renderTopPt * piece.scale
                            for (word in chosen.words) {
                                val centerYPt =
                                    ((word.box.top + word.box.bottom) / 2f + offsetYPx) / piece.scale
                                if (OcrStrips.keepsWord(piece.band, centerYPt)) {
                                    stripWords.add(
                                        com.lumen.app.data.ocr.OcrWord(
                                            text = word.text,
                                            box = android.graphics.Rect(
                                                word.box.left,
                                                (word.box.top + offsetYPx).toInt(),
                                                word.box.right,
                                                (word.box.bottom + offsetYPx).toInt(),
                                            ),
                                        )
                                    )
                                }
                            }
                            if (piece.band.isLast) {
                                val pageWPx = (piece.pageWPt * piece.scale).toInt().coerceAtLeast(1)
                                val pageHPx = (piece.pageHPt * piece.scale).toInt().coerceAtLeast(1)
                                ocrTexts[pageIndex] = OcrStrips.assembleText(
                                    stripWords.map {
                                        Triple(it.text, it.box.top.toFloat(), it.box.bottom.toFloat())
                                    }
                                )
                                ocrBoxes[pageIndex] =
                                    OcrWordBoxes.encode(stripWords.toList(), pageWPx, pageHPx)
                                stripWords.clear()
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Piece OCR failed; PdfBox text (or empty string) will be used instead
                }
            }
        }

        // Pass 3: write all pages + lines in a single transaction
        database.withTransaction {
            pages.forEach { page ->
                val finalText = if (page.needsOcr) ocrTexts[page.index] ?: page.text else page.text
                val usedOcr = page.needsOcr && ocrTexts[page.index]?.isNotEmpty() == true
                val wordCount = finalText.trim()
                    .split("\\s+".toRegex()).count { it.isNotEmpty() }
                val pageId = pageDao.insert(
                    PageEntity(
                        docId = docId,
                        pageNumber = page.index,
                        isOcr = usedOcr,
                        wordCount = wordCount,
                        wordBoxesJson = if (usedOcr) ocrBoxes[page.index] else null,
                    )
                )
                // Page-level FTS row: whole-page text so multi-word AND queries
                // match across line breaks.
                if (finalText.isNotBlank()) {
                    // @spec SEARCH-NORM-003
                    pageTextDao.insert(
                        PageTextEntity(
                            pageId = pageId,
                            text = finalText,
                            textNorm = TextNormalizer.normalize(finalText),
                        )
                    )
                }
            }
        }

        // A successful pass always recomputes the derived title and metadata
        // author from the current file; the backfill only fills never-attempted
        // title rows. A failed or encrypted pass never ran onMetadata, so
        // writing here would blank a previously-good title and author with
        // nulls — leave the carried values untouched on those outcomes.
        // @spec LIB-TTL-008, LIB-TTL-012
        if (outcome == PdfTextExtractor.Outcome.OK) {
            val pageZeroText = pages.firstOrNull { it.index == 0 }?.let { p ->
                if (p.needsOcr) ocrTexts[p.index] ?: p.text else p.text
            }
            documentDao.updateDerivedTitleAndAuthor(
                docId,
                DocumentTitles.deriveTitle(metadataTitle, pageZeroText, pdf.filename),
                DocumentTitles.sanitizeAuthor(metadataAuthor),
            )
        }

        when (outcome) {
            PdfTextExtractor.Outcome.OK ->
                documentDao.markIndexed(
                    id = docId,
                    status = DocumentEntity.STATUS_INDEXED,
                    pageCount = pages.size,
                    indexedAt = System.currentTimeMillis(),
                )
            PdfTextExtractor.Outcome.Encrypted ->
                documentDao.updateStatus(docId, DocumentEntity.STATUS_ENCRYPTED)
            PdfTextExtractor.Outcome.Error ->
                documentDao.updateStatus(docId, DocumentEntity.STATUS_ERROR)
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
        /** Tag on every index request — the handle for cancelling all index work. */
        const val TAG = "index"
        const val KEY_FOLDER_URI = "folder_uri"
        const val KEY_PROGRESS = "progress"
        const val KEY_TOTAL = "total"
        const val KEY_FORCE = "force"
        /** Presence switches the worker into single-document ephemeral mode. */
        const val KEY_EXTERNAL_DOC_URI = "external_doc_uri"
        /** Fallback filename when the provider won't answer a name query. */
        const val KEY_EXTERNAL_DISPLAY_NAME = "external_display_name"

        /** Rolling lifetime of an ephemeral external document's index rows. */
        // @spec LIB-EXT-004
        const val EPHEMERAL_TTL_MS = 7L * 24 * 60 * 60 * 1000

        private const val CHANNEL_ID = "lumen_indexing"
        private const val NOTIFICATION_ID = 1001

        // Pages yielding fewer chars from PdfBox are treated as image/scanned pages
        private const val MIN_CHARS_TEXT_PDF = 50
        // ML Kit result must have at least this many chars to skip Tesseract fallback
        private const val MIN_OCR_CHARS = 5

        /** Per-file extraction budget, tiered by size (OCR-heavy files are slow). */
        private fun indexTimeoutMs(sizeBytes: Long): Long = when {
            sizeBytes > 50 * 1024 * 1024 -> 30 * 60 * 1000L
            sizeBytes > 10 * 1024 * 1024 -> 20 * 60 * 1000L
            else -> 10 * 60 * 1000L
        }

        fun buildRequest(folderUri: Uri, force: Boolean = false): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<IndexWorker>()
                .setInputData(
                    workDataOf(
                        KEY_FOLDER_URI to folderUri.toString(),
                        KEY_FORCE to force,
                    )
                )
                .addTag(TAG)
                .build()

        /** Single-document ephemeral request. Carries the shared [TAG] so the
         *  existing cancel-all-index-work and gating paths see it too. */
        // @spec LIB-EXT-014
        fun buildExternalRequest(docUri: Uri, displayName: String? = null): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<IndexWorker>()
                .setInputData(
                    workDataOf(
                        KEY_EXTERNAL_DOC_URI to docUri.toString(),
                        KEY_EXTERNAL_DISPLAY_NAME to displayName,
                    )
                )
                .addTag(TAG)
                .build()
    }
}

/** Whether a scanned file can be trusted as unchanged against its stored row.
 *  Providers that never populate COLUMN_LAST_MODIFIED report 0 — trusting
 *  0 == 0 would skip modified files forever, so a zero falls back to size
 *  equality. Same-size edits then slip through until the next forced re-index;
 *  re-extracting every file on every scan would cost far more than that misses. */
// @spec LIB-IDX-003
internal fun sameFileContents(
    existingLastModified: Long,
    existingSize: Long,
    scannedLastModified: Long,
    scannedSize: Long,
): Boolean =
    if (scannedLastModified > 0) {
        existingLastModified == scannedLastModified
    } else {
        scannedSize > 0 && existingSize == scannedSize
    }
