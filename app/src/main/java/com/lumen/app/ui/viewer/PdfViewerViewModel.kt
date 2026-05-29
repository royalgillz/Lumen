package com.lumen.app.ui.viewer

import android.app.Application
import android.graphics.RectF
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumen.app.data.db.FtsQuerySanitizer
import com.lumen.app.data.db.dao.PageDao
import com.lumen.app.data.fs.SafRepository
import com.lumen.app.data.ocr.OcrWordBoxes
import com.lumen.app.data.pdf.PdfHighlighter
import com.lumen.app.data.repository.SearchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PdfViewerViewModel @Inject constructor(
    application: Application,
    private val pdfHighlighter: PdfHighlighter,
    private val searchRepository: SearchRepository,
    private val safRepository: SafRepository,
    private val pageDao: PageDao,
) : AndroidViewModel(application) {

    private companion object {
        /** Skip highlight computation for PDFs above this size — they OOM on
         *  mid-range devices even with MuPDF's incremental parser. */
        private const val MAX_HIGHLIGHT_PDF_BYTES = 50L * 1024L * 1024L

        /** Upper bound on match pages scanned per in-document search, to keep a
         *  query on a huge document from scanning hundreds of pages of structured
         *  text on every keystroke. */
        private const val MAX_MATCH_PAGES = 300
    }

    // ── Document lifecycle ────────────────────────────────────────────────────

    sealed class DocumentState {
        object Idle : DocumentState()
        object Loading : DocumentState()
        data class Loaded(val renderer: MuPdfPageRenderer) : DocumentState()
        object NeedsPassword : DocumentState()
        data class Failed(val message: String) : DocumentState()
    }

    private val _documentState = MutableStateFlow<DocumentState>(DocumentState.Idle)
    val documentState: StateFlow<DocumentState> = _documentState.asStateFlow()

    private var openJob: Job? = null
    private var currentRenderer: MuPdfPageRenderer? = null
    private var lastOpenedUri: String? = null
    private var lastOpenedPassword: String? = null

    fun openDocument(uriString: String, password: String? = null) {
        openJob?.cancel()
        val parsedUri = try {
            Uri.parse(uriString)
        } catch (_: Throwable) {
            _documentState.value = DocumentState.Failed("Unable to open this PDF — the link is invalid.")
            return
        }
        // Same URI + same password + already-loaded → no-op
        if (uriString == lastOpenedUri && password == lastOpenedPassword &&
            _documentState.value is DocumentState.Loaded) {
            return
        }
        lastOpenedUri = uriString
        lastOpenedPassword = password
        _documentState.value = DocumentState.Loading
        openJob = viewModelScope.launch(Dispatchers.IO) {
            // Drop any prior session.
            val prior = currentRenderer
            currentRenderer = null
            prior?.close()
            when (val res = MuPdfPageRenderer.open(getApplication(), parsedUri, password)) {
                is MuPdfPageRenderer.OpenResult.Ok -> {
                    currentRenderer = res.renderer
                    _documentState.value = DocumentState.Loaded(res.renderer)
                }
                MuPdfPageRenderer.OpenResult.NeedsPassword -> {
                    _documentState.value = DocumentState.NeedsPassword
                }
                is MuPdfPageRenderer.OpenResult.Error -> {
                    _documentState.value = DocumentState.Failed(
                        userMessageForOpenFailure(res.cause)
                    )
                }
            }
        }
    }

    fun retry() {
        val uri = lastOpenedUri ?: return
        openDocument(uri, lastOpenedPassword)
    }

    override fun onCleared() {
        super.onCleared()
        openJob?.cancel()
        currentRenderer?.close()
        currentRenderer = null
    }

    private fun userMessageForOpenFailure(cause: Throwable?): String {
        if (cause is OutOfMemoryError) return OOM_MESSAGE
        val msg = cause?.message.orEmpty()
        if (msg.contains("Failed to allocate", ignoreCase = true) ||
            msg.contains("OutOfMemory", ignoreCase = true) ||
            msg.contains("OOM", ignoreCase = true)) return OOM_MESSAGE
        if (msg.contains("permission", ignoreCase = true)) {
            return "Permission denied.\nGo to Library, remove this folder, and re-add it to restore access."
        }
        if (msg.contains("FileNotFound", ignoreCase = true) || msg.contains("No such file", ignoreCase = true)) {
            return "File not found.\nThis PDF may have been moved or deleted."
        }
        return if (msg.isNotBlank()) msg else "Unable to open this PDF."
    }

    // ── In-document search (occurrence model) ─────────────────────────────────
    //
    // The MuPDF highlighter is the single source of truth: we enumerate every real
    // occurrence rect across the candidate pages, so the count, the active index,
    // and the drawn rects can never disagree. Navigation walks a flat list, so it
    // is synchronous and immune to fast-tap races and to page/text mismatches.

    data class Occurrence(val page: Int, val rectIndex: Int)

    private val _occurrences = MutableStateFlow<List<Occurrence>>(emptyList())
    val occurrences: StateFlow<List<Occurrence>> = _occurrences.asStateFlow()

    private val _activeOccurrence = MutableStateFlow(0)
    val activeOccurrence: StateFlow<Int> = _activeOccurrence.asStateFlow()

    /** Page to bring into view for the active occurrence; -1 when none. */
    private val _activePage = MutableStateFlow(-1)
    val activePage: StateFlow<Int> = _activePage.asStateFlow()

    /** Rect index of the active occurrence within its page; -1 when none. */
    private val _activeRectIndexOnPage = MutableStateFlow(-1)
    val activeRectIndexOnPage: StateFlow<Int> = _activeRectIndexOnPage.asStateFlow()

    /** Per-page highlight rects for drawing, keyed by page index. */
    private val _pageHighlights = MutableStateFlow<Map<Int, PdfHighlighter.PageHighlights>>(emptyMap())
    val pageHighlights: StateFlow<Map<Int, PdfHighlighter.PageHighlights>> = _pageHighlights.asStateFlow()

    private val _highlightSkipped = MutableStateFlow(false)
    val highlightSkipped: StateFlow<Boolean> = _highlightSkipped.asStateFlow()

    private var searchJob: Job? = null

    fun highlightsForPage(page: Int): PdfHighlighter.PageHighlights? = _pageHighlights.value[page]

    /**
     * Enumerate every occurrence of [keyword] across the document's match pages and
     * position the active occurrence. [preferredPage]/[preferredOccurrence] let a
     * global-search result open directly on the exact match the user tapped.
     */
    fun runInDocumentSearch(
        docUri: String,
        keyword: String,
        password: String? = null,
        preferredPage: Int? = null,
        preferredOccurrence: Int = 0,
    ) {
        searchJob?.cancel()
        val trimmed = keyword.trim()
        if (trimmed.length < 2) {
            resetSearch()
            return
        }
        val parsedUri = runCatching { Uri.parse(docUri) }.getOrNull() ?: run { resetSearch(); return }
        if (!canSafelyComputeHighlights(parsedUri)) {
            resetSearch()
            _highlightSkipped.value = true
            return
        }
        _highlightSkipped.value = false
        val sanitized = FtsQuerySanitizer.sanitize(trimmed) ?: run { resetSearch(); return }
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            val pages = searchRepository.searchPagesInDocument(sanitized, docUri).take(MAX_MATCH_PAGES)
            // Scanned (OCR) pages have no PDF text layer, so MuPDF finds nothing —
            // use the word boxes captured at index time. Text-layer pages use MuPDF
            // (char-precise). Both produce the same PageHighlights shape.
            val ocrHls = ocrHighlightsForPages(docUri, pages, trimmed)
            val textPages = pages.filter { it !in ocrHls.keys }
            val textHls = pdfHighlighter.findOnPages(parsedUri, textPages, trimmed, password)
                .associateBy { it.pageIndex }
            val cache = LinkedHashMap<Int, PdfHighlighter.PageHighlights>()
            val occ = ArrayList<Occurrence>()
            for (page in pages) {
                val ph = ocrHls[page] ?: textHls[page]
                if (ph == null || ph.rects.isEmpty()) continue
                cache[page] = ph
                for (r in ph.rects.indices) occ.add(Occurrence(page, r))
            }
            _pageHighlights.value = cache
            _occurrences.value = occ
            val startIdx = when {
                occ.isEmpty() -> 0
                preferredPage != null -> {
                    val first = occ.indexOfFirst { it.page == preferredPage }
                    if (first < 0) {
                        0
                    } else {
                        val onPage = cache[preferredPage]?.rects?.size ?: 1
                        first + preferredOccurrence.coerceIn(0, onPage - 1)
                    }
                }
                else -> 0
            }
            setActiveOccurrence(startIdx)
        }
    }

    /**
     * Build highlight rects for scanned pages from the OCR word boxes stored at index
     * time. Boxes are normalised, so we scale them by each page's point size (from the
     * open renderer) into the same coordinate space the text-layer path uses.
     */
    private suspend fun ocrHighlightsForPages(
        docUri: String,
        pages: List<Int>,
        keyword: String,
    ): Map<Int, PdfHighlighter.PageHighlights> {
        if (pages.isEmpty()) return emptyMap()
        val renderer = currentRenderer ?: return emptyMap()
        val needle = keyword.trim().lowercase()
        if (needle.isEmpty()) return emptyMap()
        val rows = runCatching { pageDao.ocrWordBoxes(docUri, pages) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<Int, PdfHighlighter.PageHighlights>()
        for (row in rows) {
            val matched = OcrWordBoxes.decode(row.wordBoxesJson)
                .filter { it.text.lowercase().contains(needle) }
            if (matched.isEmpty()) continue
            val size = renderer.pageSize(row.pageNumber) ?: continue
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) continue
            val rects = matched.map { RectF(it.left * w, it.top * h, it.right * w, it.bottom * h) }
            out[row.pageNumber] = PdfHighlighter.PageHighlights(row.pageNumber, rects, w, h)
        }
        return out
    }

    fun nextOccurrence() {
        val n = _occurrences.value.size
        if (n == 0) return
        setActiveOccurrence((_activeOccurrence.value + 1) % n)
    }

    fun prevOccurrence() {
        val n = _occurrences.value.size
        if (n == 0) return
        setActiveOccurrence((_activeOccurrence.value - 1 + n) % n)
    }

    fun clearSearch() = resetSearch()

    fun resetHighlightSkipped() {
        _highlightSkipped.value = false
    }

    private fun setActiveOccurrence(index: Int) {
        val occ = _occurrences.value
        if (occ.isEmpty()) {
            _activeOccurrence.value = 0
            _activePage.value = -1
            _activeRectIndexOnPage.value = -1
            return
        }
        val i = index.coerceIn(0, occ.size - 1)
        _activeOccurrence.value = i
        _activePage.value = occ[i].page
        _activeRectIndexOnPage.value = occ[i].rectIndex
    }

    private fun resetSearch() {
        searchJob?.cancel()
        _occurrences.value = emptyList()
        _pageHighlights.value = emptyMap()
        _activeOccurrence.value = 0
        _activePage.value = -1
        _activeRectIndexOnPage.value = -1
    }

    // ── Scroll mode ───────────────────────────────────────────────────────────

    val scrollHorizontal: StateFlow<Boolean> = safRepository.viewerScrollHorizontal
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun toggleScrollMode() {
        viewModelScope.launch {
            safRepository.setViewerScrollHorizontal(!scrollHorizontal.value)
        }
    }

    // ── Reading progress ──────────────────────────────────────────────────────

    fun saveLastPage(uri: String, page: Int) {
        viewModelScope.launch { safRepository.saveLastPage(uri, page) }
    }

    suspend fun getLastPage(uri: String): Int? = safRepository.getLastPage(uri)

    private fun canSafelyComputeHighlights(uri: Uri): Boolean {
        val resolver = getApplication<Application>().contentResolver
        val length = runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                val reported = afd.length
                if (reported >= 0) reported else null
            }
        }.getOrNull()
        return length?.let { it <= MAX_HIGHLIGHT_PDF_BYTES } ?: true
    }
}

private const val OOM_MESSAGE =
    "This PDF requires too much memory to display. Close other apps and try again, " +
        "or view it on a PC if it contains many high-resolution scanned pages."
