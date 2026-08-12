package com.lumen.app.ui.viewer

import android.app.Application
import android.graphics.RectF
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumen.app.data.db.FtsQuerySanitizer
import com.lumen.app.data.db.dao.BookmarkDao
import com.lumen.app.data.db.dao.DocumentDao
import com.lumen.app.data.db.dao.DocumentTitleDao
import com.lumen.app.data.db.dao.PageDao
import com.lumen.app.data.db.entity.BookmarkEntity
import com.lumen.app.data.db.entity.DocumentEntity
import com.lumen.app.data.fs.SafRepository
import com.lumen.app.data.ocr.OcrWordBoxes
import com.lumen.app.data.pdf.PdfHighlighter
import com.lumen.app.data.repository.SearchRepository
import com.lumen.app.data.text.NormalizedMatcher
import com.lumen.app.di.ApplicationScope
import com.lumen.app.domain.model.DocumentTitles
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    private val documentDao: DocumentDao,
    private val documentTitleDao: DocumentTitleDao,
    private val bookmarkDao: BookmarkDao,
    @ApplicationScope private val appScope: CoroutineScope,
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
        /** [wrongPassword] is true when a password was supplied and rejected, so
         *  the prompt can say "incorrect" rather than silently reopening. */
        data class NeedsPassword(val wrongPassword: Boolean = false) : DocumentState()
        data class Failed(val message: String) : DocumentState()
    }

    private val _documentState = MutableStateFlow<DocumentState>(DocumentState.Idle)
    val documentState: StateFlow<DocumentState> = _documentState.asStateFlow()

    // Resolved display title (customTitle > derivedTitle > filename) for the
    // top bar; null until resolved or when the document has no library row.
    private val _displayTitle = MutableStateFlow<String?>(null)
    val displayTitle: StateFlow<String?> = _displayTitle.asStateFlow()

    private var openJob: Job? = null
    private var currentRenderer: MuPdfPageRenderer? = null
    private var lastOpenedUri: String? = null
    private var lastOpenedPassword: String? = null

    /** URI of the open document as observable state, driving the bookmarks flow. */
    private val openedUri = MutableStateFlow<String?>(null)

    /**
     * The page saved from a previous session, captured once per document open
     * BEFORE any save from this session can overwrite it — reading it lazily from
     * the screen raced the first onPageChanged save, which on small documents won
     * and silently suppressed the resume snackbar.
     */
    var resumePage: Int? = null
        private set

    /** Page currently on screen; survives rotation so the recreated view can
     *  reopen where the user actually was, not at the nav-arg page. */
    var lastViewedPage: Int = -1
        private set

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
        // Different document reusing this ViewModel (launchSingleTop replaces the
        // nav entry's arguments in place, e.g. an external VIEW intent while the
        // viewer is open): drop every per-document remnant, or the new PDF opens
        // at the old one's page with the old one's search state.
        if (lastOpenedUri != null && uriString != lastOpenedUri) {
            resumePage = null
            lastViewedPage = -1
            savePageJob?.cancel()
            resetSearch()
        }
        lastOpenedUri = uriString
        lastOpenedPassword = password
        openedUri.value = uriString
        _documentState.value = DocumentState.Loading
        openJob = viewModelScope.launch(Dispatchers.IO) {
            if (resumePage == null) resumePage = safRepository.getLastPage(uriString) ?: -1
            // Drop any prior session.
            val prior = currentRenderer
            currentRenderer = null
            prior?.close()
            when (val res = MuPdfPageRenderer.open(getApplication(), parsedUri, password)) {
                is MuPdfPageRenderer.OpenResult.Ok -> {
                    currentRenderer = res.renderer
                    _documentState.value = DocumentState.Loaded(res.renderer)
                    // User recency: once per genuine open (the same-document guard
                    // upstream suppresses rotation re-opens); silently no-ops for
                    // documents with no library row (external VIEW-intent opens).
                    // @spec LIB-REC-001
                    appScope.launch {
                        runCatching {
                            documentDao.markOpened(parsedUri.toString(), System.currentTimeMillis())
                        }
                    }
                    // Top-bar title: resolved from the title store by URI; the
                    // screen falls back to its filename argument (which keeps
                    // serving file operations) when no row exists.
                    // @spec SEARCH-UI-006
                    viewModelScope.launch {
                        runCatching {
                            val uriStr = parsedUri.toString()
                            val doc = documentDao.getByUri(uriStr)
                            _displayTitle.value = doc?.let {
                                DocumentTitles.displayTitle(
                                    documentTitleDao.getTitle(uriStr),
                                    it.derivedTitle,
                                    it.filename,
                                )
                            }
                        }
                    }
                }
                MuPdfPageRenderer.OpenResult.NeedsPassword -> {
                    _documentState.value =
                        DocumentState.NeedsPassword(wrongPassword = !password.isNullOrEmpty())
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

    /**
     * One-shot "scroll the view to this page" requests, emitted only by explicit
     * search actions (a new search landing, next/prev navigation). Events rather
     * than state: re-collecting state after rotation would re-jump the view to the
     * match page and lose the reader's restored position.
     */
    private val _scrollToPage = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val scrollToPage: SharedFlow<Int> = _scrollToPage.asSharedFlow()

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
        val trimmed = keyword.trim()
        // Same live search (e.g. the screen recomposing after rotation): keep the
        // existing match state instead of resetting and re-jumping to a match page.
        if (docUri == searchDocUri && trimmed == searchKeyword && _matchPages.value.isNotEmpty()) {
            return
        }
        searchJob?.cancel()
        countJob?.cancel()
        navJob?.cancel()
        if (trimmed.length < 2) {
            resetSearch()
            return
        }
        val parsedUri = runCatching { Uri.parse(docUri) }.getOrNull() ?: run { resetSearch(); return }
        // Same gate as content search: no token with ≥ 2 normalized chars → no
        // search (sanitize returns null for those and for zero-token queries).
        // @spec VIEW-MATCH-005
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
                // The FTS index knows nothing about documents that were never
                // indexed — opened directly via a VIEW intent (e.g. a mail
                // attachment), or encrypted/errored at index time. For those, fall
                // back to scanning the open document's text page by page.
                val scanned = if (isSearchableInIndex(docUri)) emptyList() else scanOpenDocument(trimmed)
                if (scanned.isEmpty()) {
                    _activePage.value = -1
                    _activeRectIndexOnPage.value = -1
                    return@launch
                }
                recompute()
                startCountPass()
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
                    _scrollToPage.tryEmit(p)
                    landed = true
                    break
                }
            }
            if (!landed) {
                // Pages matched but none yielded rects (e.g. all heavy text pages that
                // OOM-skipped). Still position on the start page so search "works".
                _activePage.value = startPage
                _activeRectIndexOnPage.value = -1
                _scrollToPage.tryEmit(startPage)
            }
            recompute()
            startCountPass()
        }
    }

    /** True while the fallback page-by-page scan is running, so the UI can show
     *  "Searching…" instead of a premature "No matches". */
    private val _isScanningFallback = MutableStateFlow(false)
    val isScanningFallback: StateFlow<Boolean> = _isScanningFallback.asStateFlow()

    /** A document only trusts the FTS index for search when it finished indexing;
     *  missing or encrypted/errored documents get the fallback scan. */
    private suspend fun isSearchableInIndex(docUri: String): Boolean {
        val doc = runCatching { documentDao.getByUri(docUri) }.getOrNull()
        return doc != null && doc.status == DocumentEntity.STATUS_INDEXED
    }

    /**
     * Fallback in-document search for unindexed documents: walk every page's
     * MuPDF structured text with token-AND matching (mirroring FTS semantics —
     * every query token somewhere on the page). Publishes match pages
     * progressively and lands on the first match as soon as it's found, so the
     * reader isn't waiting on a full scan of a large file. Scanned pages without
     * a text layer yield no words and simply never match.
     */
    // @spec VIEW-MATCH-003
    private suspend fun scanOpenDocument(keyword: String): List<Int> {
        val renderer = currentRenderer ?: return emptyList()
        val needles = NormalizedMatcher.tokensOf(keyword)
        if (needles.isEmpty()) return emptyList()
        val found = mutableListOf<Int>()
        _isScanningFallback.value = true
        try {
            for (p in 0 until renderer.pageCount) {
                currentCoroutineContext().ensureActive()
                val words = runCatching { renderer.wordsForPage(p) }.getOrNull().orEmpty()
                if (words.isEmpty()) continue
                val matches = needles.all { n ->
                    words.any { NormalizedMatcher.findMatches(it.text, n).isNotEmpty() }
                }
                if (!matches) continue
                found += p
                _matchPages.value = found.toList()
                if (found.size == 1) {
                    // Land immediately on the first hit while the scan continues.
                    val n = rectsFor(p).rects.size
                    _activePage.value = p
                    _activeRectIndexOnPage.value = if (n > 0) 0 else -1
                    _scrollToPage.tryEmit(p)
                    recompute()
                }
                if (found.size >= MAX_MATCH_PAGES) break
            }
        } finally {
            _isScanningFallback.value = false
        }
        return found
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

    /** OCR page → DB boxes (free); else text-layer page → lazy MuPDF rects,
     *  preferring the viewer's already-open document over a per-page reopen. */
    private suspend fun computePageHighlights(
        uri: Uri,
        page: Int,
        keyword: String,
        password: String?,
    ): PdfHighlighter.PageHighlights {
        ocrHighlightsForPage(page, keyword)?.let { return it }
        currentRenderer?.let { renderer ->
            runCatching {
                renderer.withDocumentGated { doc ->
                    pdfHighlighter.findOnPageInDocument(doc, page, keyword)
                }
            }.getOrNull()?.let { return it }
        }
        // Renderer closed / not ready — fall back to a one-shot open.
        return runCatching { pdfHighlighter.findOnPage(uri, page, keyword, password) }
            .getOrElse { PdfHighlighter.PageHighlights(page, emptyList(), 0f, 0f) }
    }

    /**
     * Highlight rects for a single scanned page from its stored OCR word boxes.
     * Returns null when [page] is not an OCR page (caller falls back to text);
     * returns a (possibly empty-rect) result when it is, so we never recompute it.
     */
    // @spec VIEW-MATCH-002
    private suspend fun ocrHighlightsForPage(page: Int, keyword: String): PdfHighlighter.PageHighlights? {
        val renderer = currentRenderer ?: return null
        // Token-based like the text-layer path: OCR boxes are per word, so a
        // multi-word query can never match a single box — match each token instead,
        // through the same NormalizedMatcher ("f1" must find a box reading "F-1").
        val needles = NormalizedMatcher.tokensOf(keyword)
        if (needles.isEmpty()) return null
        val rows = runCatching { pageDao.ocrWordBoxes(searchDocUri, listOf(page)) }.getOrNull()
        val row = rows?.firstOrNull() ?: return null
        val size = renderer.pageSize(page) ?: return PdfHighlighter.PageHighlights(page, emptyList(), 0f, 0f)
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return PdfHighlighter.PageHighlights(page, emptyList(), 0f, 0f)
        val rects = OcrWordBoxes.decode(row.wordBoxesJson)
            .filter { box -> needles.any { NormalizedMatcher.findMatches(box.text, it).isNotEmpty() } }
            .map { RectF(it.left * w, it.top * h, it.right * w, it.bottom * h) }
        return PdfHighlighter.PageHighlights(page, rects, w, h)
    }

    /**
     * Words for long-press selection on [page]: structured text when the page has
     * a text layer, else the OCR word boxes stored at index time — so selection
     * also works on scanned pages. Keyed by the open document's own URI (not the
     * search context, which only exists during an in-document search).
     */
    suspend fun wordsForPage(page: Int): List<MuPdfPageRenderer.WordBox> {
        val renderer = currentRenderer
        val textWords = renderer?.let { runCatching { it.wordsForPage(page) }.getOrNull() }.orEmpty()
        if (textWords.isNotEmpty()) return textWords
        val docUri = lastOpenedUri ?: return emptyList()
        val rows = runCatching { pageDao.ocrWordBoxes(docUri, listOf(page)) }.getOrNull()
        val row = rows?.firstOrNull() ?: return emptyList()
        val size = renderer?.pageSize(page) ?: return emptyList()
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return emptyList()
        return OcrWordBoxes.decode(row.wordBoxesJson).map { box ->
            MuPdfPageRenderer.WordBox(
                box.text,
                RectF(box.left * w, box.top * h, box.right * w, box.bottom * h),
            )
        }
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
        // IO, not Main: an uncached page runs native structured-text extraction here.
        navJob = viewModelScope.launch(Dispatchers.IO) {
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
                    _scrollToPage.tryEmit(p)
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

    // ── Bookmarks ─────────────────────────────────────────────────────────────

    sealed class BookmarkEvent {
        data class Added(val id: Long, val page: Int) : BookmarkEvent()
        data class Removed(val page: Int) : BookmarkEvent()
    }

    /** One-shot add/remove notifications driving the snackbar ("Bookmarked p. N —
     *  Add note"). Events, not state: replaying on rotation would re-show it. */
    private val _bookmarkEvents = MutableSharedFlow<BookmarkEvent>(extraBufferCapacity = 4)
    val bookmarkEvents: SharedFlow<BookmarkEvent> = _bookmarkEvents.asSharedFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val bookmarks: StateFlow<List<BookmarkEntity>> = openedUri
        .flatMapLatest { uri ->
            if (uri == null) flowOf(emptyList()) else bookmarkDao.observeForDocument(uri)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Toggle a bookmark on [page]: adds when absent, removes when present. */
    fun toggleBookmark(page: Int) {
        val uri = lastOpenedUri ?: return
        if (page < 0) return
        viewModelScope.launch(Dispatchers.IO) {
            val existing = bookmarkDao.getByPage(uri, page)
            if (existing != null) {
                bookmarkDao.deleteById(existing.id)
                _bookmarkEvents.tryEmit(BookmarkEvent.Removed(page))
            } else {
                val id = bookmarkDao.insert(
                    BookmarkEntity(docUri = uri, pageNumber = page, createdAt = System.currentTimeMillis())
                )
                _bookmarkEvents.tryEmit(BookmarkEvent.Added(id, page))
            }
        }
    }

    fun setBookmarkNote(id: Long, note: String) {
        viewModelScope.launch(Dispatchers.IO) {
            bookmarkDao.updateNote(id, note.trim().ifBlank { null })
        }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { bookmarkDao.deleteById(id) }
    }

    // ── Reading progress ──────────────────────────────────────────────────────

    private var savePageJob: Job? = null

    /**
     * Record the page currently on screen. The DataStore write is debounced (a
     * fling emits every page it crosses) and runs on the application scope so the
     * final position lands even if the user exits the viewer inside the window.
     */
    fun noteCurrentPage(uri: String, page: Int) {
        lastViewedPage = page
        savePageJob?.cancel()
        savePageJob = appScope.launch {
            delay(400)
            safRepository.saveLastPage(uri, page)
        }
    }
}

private const val OOM_MESSAGE =
    "This PDF requires too much memory to display. Close other apps and try again, " +
        "or view it on a PC if it contains many high-resolution scanned pages."
