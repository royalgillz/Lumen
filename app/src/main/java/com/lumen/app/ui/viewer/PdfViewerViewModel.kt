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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.withLock
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
        /** Upper bound on the match-page navigation list per in-document search.
         *  Bounds the page list + lazy compute fan-out, not an up-front scan. */
        private const val MAX_MATCH_PAGES = 1000
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

    // ── In-document search (match-page model, lazy per-page highlights) ────────
    //
    // Navigation is page-based: the FTS index gives the matching pages cheaply
    // (size-independent), and the user steps page to page. Highlight rects are
    // produced per page on demand:
    //   • OCR/scanned page → from word boxes stored at index time (a free DB read);
    //     always drawn, no size check.
    //   • Text-layer page  → MuPDF structured text for that one page only, computed
    //     lazily when the page is visited, gated, with a per-page OOM catch. If a
    //     single heavy page fails it yields empty rects and search still navigates
    //     there; it just doesn't paint on that page.
    // No whole-file size gate exists: search and OCR highlights work at any size.

    private val _matchPages = MutableStateFlow<List<Int>>(emptyList())
    val matchPages: StateFlow<List<Int>> = _matchPages.asStateFlow()

    /** 1-based ordinal of the active occurrence across the whole document; 0 = none. */
    private val _occurrenceOrdinal = MutableStateFlow(0)
    val occurrenceOrdinal: StateFlow<Int> = _occurrenceOrdinal.asStateFlow()

    /** Total occurrences known so far. Grows as the background gated counting pass
     *  finishes scanning match pages; settles at the true total. */
    private val _occurrenceTotal = MutableStateFlow(0)
    val occurrenceTotal: StateFlow<Int> = _occurrenceTotal.asStateFlow()

    /** Page to bring into view for the active occurrence; -1 when none. */
    private val _activePage = MutableStateFlow(-1)
    val activePage: StateFlow<Int> = _activePage.asStateFlow()

    /** Rect on the active page to emphasise; -1 when none / not yet known. */
    private val _activeRectIndexOnPage = MutableStateFlow(-1)
    val activeRectIndexOnPage: StateFlow<Int> = _activeRectIndexOnPage.asStateFlow()

    /** Lazily-populated per-page highlight rects, keyed by page index. An entry with
     *  empty rects means "computed, nothing to draw" and is not recomputed. */
    private val _pageHighlights = MutableStateFlow<Map<Int, PdfHighlighter.PageHighlights>>(emptyMap())
    val pageHighlights: StateFlow<Map<Int, PdfHighlighter.PageHighlights>> = _pageHighlights.asStateFlow()

    private var searchJob: Job? = null
    private var countJob: Job? = null
    private var navJob: Job? = null
    // Serialises mutations to the rect cache + derived counts across the count pass,
    // navigation, and on-demand page computes.
    private val cacheMutex = kotlinx.coroutines.sync.Mutex()

    // Context retained so a page's rects can be computed lazily on visit.
    private var searchDocUri: String = ""
    private var searchUri: Uri? = null
    private var searchKeyword: String = ""
    private var searchPassword: String? = null

    /**
     * Run an in-document search for [keyword]. Always proceeds regardless of file
     * size. Lists matching pages from the FTS index, positions the active occurrence,
     * and kicks off a background gated pass that counts occurrences per page so the
     * total settles to its true value. [preferredPage]/[preferredOccurrence] let a
     * global-search result open on the exact occurrence the user tapped.
     */
    fun runInDocumentSearch(
        docUri: String,
        keyword: String,
        password: String? = null,
        preferredPage: Int? = null,
        preferredOccurrence: Int = 0,
    ) {
        searchJob?.cancel()
        countJob?.cancel()
        navJob?.cancel()
        val trimmed = keyword.trim()
        if (trimmed.length < 2) {
            resetSearch()
            return
        }
        val parsedUri = runCatching { Uri.parse(docUri) }.getOrNull() ?: run { resetSearch(); return }
        val sanitized = FtsQuerySanitizer.sanitize(trimmed) ?: run { resetSearch(); return }
        searchDocUri = docUri
        searchUri = parsedUri
        searchKeyword = trimmed
        searchPassword = password
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            val pages = searchRepository.searchPagesInDocument(sanitized, docUri).take(MAX_MATCH_PAGES)
            _pageHighlights.value = emptyMap()
            _occurrenceTotal.value = 0
            _occurrenceOrdinal.value = 0
            _matchPages.value = pages
            if (pages.isEmpty()) {
                _activePage.value = -1
                _activeRectIndexOnPage.value = -1
                return@launch
            }
            // Land on the first occurrence at/after the preferred page (wrapping),
            // so a page that matched in FTS but has no drawable rects is skipped.
            val startPage = preferredPage?.takeIf { it in pages } ?: pages.first()
            val startIdx = pages.indexOf(startPage)
            var landed = false
            for (off in 0 until pages.size) {
                val p = pages[(startIdx + off) % pages.size]
                val n = rectsFor(p).rects.size
                if (n > 0) {
                    _activePage.value = p
                    _activeRectIndexOnPage.value =
                        if (p == startPage) preferredOccurrence.coerceIn(0, n - 1) else 0
                    landed = true
                    break
                }
            }
            if (!landed) {
                // Pages matched but none yielded rects (e.g. all heavy text pages that
                // OOM-skipped). Still position on the start page so search "works".
                _activePage.value = startPage
                _activeRectIndexOnPage.value = -1
            }
            recompute()
            startCountPass()
        }
    }

    /**
     * Ensure rects for [page] are computed and cached, if [page] is a match page and
     * not already done. Called when a match page is scrolled to, so its highlights
     * paint even if the background count pass hasn't reached it yet.
     */
    fun ensurePageHighlights(page: Int) {
        if (page < 0 || page !in _matchPages.value) return
        if (_pageHighlights.value.containsKey(page)) return
        if (searchKeyword.length < 2) return
        viewModelScope.launch(Dispatchers.IO) { rectsFor(page) }
    }

    /**
     * Background gated pass: compute rects for every match page, one at a time, so the
     * occurrence total settles. Each page goes through [rectsFor], which shares the
     * single MuPDF render permit and catches per-page OOM — safe on huge files.
     */
    private fun startCountPass() {
        countJob?.cancel()
        countJob = viewModelScope.launch(Dispatchers.IO) {
            for (p in _matchPages.value) {
                if (!isActive) break
                rectsFor(p)
            }
        }
    }

    /**
     * Return rects for [page], computing and caching on first request. Cache writes
     * and the derived occurrence counts are guarded by [cacheMutex] so the count
     * pass, navigation, and scroll-driven computes don't race.
     */
    private suspend fun rectsFor(page: Int): PdfHighlighter.PageHighlights {
        _pageHighlights.value[page]?.let { return it }
        val uri = searchUri ?: return PdfHighlighter.PageHighlights(page, emptyList(), 0f, 0f)
        val keyword = searchKeyword
        if (keyword.length < 2) return PdfHighlighter.PageHighlights(page, emptyList(), 0f, 0f)
        val computed = computePageHighlights(uri, page, keyword, searchPassword)
        return cacheMutex.withLock {
            _pageHighlights.value[page] ?: run {
                _pageHighlights.value = _pageHighlights.value + (page to computed)
                recomputeCounts()
                computed
            }
        }
    }

    /** OCR page → DB boxes (free); else text-layer page → lazy MuPDF rects. */
    private suspend fun computePageHighlights(
        uri: Uri,
        page: Int,
        keyword: String,
        password: String?,
    ): PdfHighlighter.PageHighlights {
        ocrHighlightsForPage(page, keyword)?.let { return it }
        return runCatching { pdfHighlighter.findOnPage(uri, page, keyword, password) }
            .getOrElse { PdfHighlighter.PageHighlights(page, emptyList(), 0f, 0f) }
    }

    /**
     * Highlight rects for a single scanned page from its stored OCR word boxes.
     * Returns null when [page] is not an OCR page (caller falls back to text);
     * returns a (possibly empty-rect) result when it is, so we never recompute it.
     */
    private suspend fun ocrHighlightsForPage(page: Int, keyword: String): PdfHighlighter.PageHighlights? {
        val renderer = currentRenderer ?: return null
        val needle = keyword.lowercase()
        if (needle.isEmpty()) return null
        val rows = runCatching { pageDao.ocrWordBoxes(searchDocUri, listOf(page)) }.getOrNull()
        val row = rows?.firstOrNull() ?: return null
        val size = renderer.pageSize(page) ?: return PdfHighlighter.PageHighlights(page, emptyList(), 0f, 0f)
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return PdfHighlighter.PageHighlights(page, emptyList(), 0f, 0f)
        val rects = OcrWordBoxes.decode(row.wordBoxesJson)
            .filter { it.text.lowercase().contains(needle) }
            .map { RectF(it.left * w, it.top * h, it.right * w, it.bottom * h) }
        return PdfHighlighter.PageHighlights(page, rects, w, h)
    }

    fun nextOccurrence() = step(+1)

    fun prevOccurrence() = step(-1)

    /**
     * Step one occurrence in [dir] (+1 next, -1 prev). Walks rects within the active
     * page first, then advances to the adjacent match page that has rects (computing
     * lazily, wrapping around), skipping match pages with no drawable rects.
     */
    private fun step(dir: Int) {
        val pages = _matchPages.value
        if (pages.isEmpty()) return
        navJob?.cancel()
        navJob = viewModelScope.launch(Dispatchers.Main.immediate) {
            val page = _activePage.value
            val curRects = if (page >= 0) rectsFor(page).rects.size else 0
            val target = _activeRectIndexOnPage.value + dir
            if (page >= 0 && target in 0 until curRects) {
                _activeRectIndexOnPage.value = target
                recompute()
                return@launch
            }
            val base = pages.indexOf(page).coerceAtLeast(0)
            for (off in 1..pages.size) {
                val p = pages[((base + dir * off) % pages.size + pages.size) % pages.size]
                val n = rectsFor(p).rects.size
                if (n > 0) {
                    _activePage.value = p
                    _activeRectIndexOnPage.value = if (dir > 0) 0 else n - 1
                    recompute()
                    return@launch
                }
            }
        }
    }

    fun clearSearch() = resetSearch()

    /** Recompute the displayed ordinal/total from the rects cached so far. Total only
     *  counts computed pages, so it grows as the count pass fills in. */
    private fun recomputeCounts() {
        val pages = _matchPages.value
        val hl = _pageHighlights.value
        var total = 0
        for (p in pages) total += hl[p]?.rects?.size ?: 0
        val active = _activePage.value
        val rect = _activeRectIndexOnPage.value
        val ordinal = if (active < 0 || rect < 0) {
            0
        } else {
            var before = 0
            for (p in pages) {
                if (p == active) break
                before += hl[p]?.rects?.size ?: 0
            }
            before + rect + 1
        }
        _occurrenceOrdinal.value = ordinal
        _occurrenceTotal.value = maxOf(total, ordinal)
    }

    private suspend fun recompute() = cacheMutex.withLock { recomputeCounts() }

    private fun resetSearch() {
        searchJob?.cancel()
        countJob?.cancel()
        navJob?.cancel()
        _matchPages.value = emptyList()
        _pageHighlights.value = emptyMap()
        _occurrenceOrdinal.value = 0
        _occurrenceTotal.value = 0
        _activePage.value = -1
        _activeRectIndexOnPage.value = -1
        searchDocUri = ""
        searchUri = null
        searchKeyword = ""
        searchPassword = null
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
}

private const val OOM_MESSAGE =
    "This PDF requires too much memory to display. Close other apps and try again, " +
        "or view it on a PC if it contains many high-resolution scanned pages."
