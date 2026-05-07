package com.lumen.app.ui.viewer

import android.app.Application
import android.graphics.Bitmap
import android.graphics.PointF
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumen.app.data.db.FtsQuerySanitizer
import com.lumen.app.data.fs.SafRepository
import com.lumen.app.data.pdf.PdfHighlighter
import com.lumen.app.data.repository.SearchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class PdfViewerViewModel @Inject constructor(
    application: Application,
    private val pdfHighlighter: PdfHighlighter,
    private val searchRepository: SearchRepository,
    private val safRepository: SafRepository,
) : AndroidViewModel(application) {
    private companion object {
        // Highlights use PDFBox parsing; skip on very large files to avoid OOM.
        private const val MAX_HIGHLIGHT_PDF_BYTES = 25L * 1024L * 1024L
    }

    // ── Keyword highlights ────────────────────────────────────────────────────

    private val _highlights = MutableStateFlow<PdfHighlighter.PageHighlights?>(null)
    val highlights: StateFlow<PdfHighlighter.PageHighlights?> = _highlights

    private val _highlightSkipped = MutableStateFlow(false)
    val highlightSkipped: StateFlow<Boolean> = _highlightSkipped

    private var highlightJob: Job? = null

    fun loadHighlights(uri: String, pageIndex: Int, keyword: String, pdfPassword: String? = null) {
        if (keyword.isBlank()) {
            clearHighlights()
            return
        }
        val parsedUri = try { Uri.parse(uri) } catch (_: Exception) { return }
        if (!canSafelyComputeHighlights(parsedUri)) {
            _highlights.value = null
            _highlightSkipped.value = true
            return
        }
        _highlightSkipped.value = false
        highlightJob?.cancel()
        highlightJob = viewModelScope.launch(Dispatchers.IO) {
            _highlights.value = null
            val stream = try {
                getApplication<Application>().contentResolver.openInputStream(parsedUri)
            } catch (_: Exception) { null } ?: return@launch
            _highlights.value = try {
                stream.use { pdfHighlighter.findOnPage(it, pageIndex, keyword, pdfPassword) }
            } catch (_: OutOfMemoryError) {
                null
            } catch (_: Exception) {
                null
            }
        }
    }

    fun clearHighlights() {
        highlightJob?.cancel()
        _highlights.value = null
    }

    fun resetHighlightSkipped() {
        _highlightSkipped.value = false
    }

    // ── In-document search ────────────────────────────────────────────────────

    private val _viewerMatchPages = MutableStateFlow<List<Int>>(emptyList())
    val viewerMatchPages: StateFlow<List<Int>> = _viewerMatchPages

    val viewerMatchIndex = MutableStateFlow(0)

    private var inDocSearchJob: Job? = null
    private val _pageCount = MutableStateFlow(0)
    val pageCount: StateFlow<Int> = _pageCount
    private val rendererMutex = Mutex()
    private var rendererSession: MuPdfPageRenderer? = null
    private var rendererUri: String? = null
    private var rendererPassword: String? = null

    fun searchInDocument(docUri: String, rawQuery: String) {
        if (rawQuery.isBlank() || rawQuery.trim().length < 2) {
            _viewerMatchPages.value = emptyList()
            viewerMatchIndex.value = 0
            return
        }
        val sanitized = FtsQuerySanitizer.sanitize(rawQuery.trim()) ?: run {
            _viewerMatchPages.value = emptyList()
            return
        }
        inDocSearchJob?.cancel()
        inDocSearchJob = viewModelScope.launch(Dispatchers.IO) {
            _viewerMatchPages.value = searchRepository.searchPagesInDocument(sanitized, docUri)
            viewerMatchIndex.value = 0
        }
    }

    fun nextMatch() {
        val pages = _viewerMatchPages.value
        if (pages.isEmpty()) return
        viewerMatchIndex.value = (viewerMatchIndex.value + 1) % pages.size
    }

    fun prevMatch() {
        val pages = _viewerMatchPages.value
        if (pages.isEmpty()) return
        viewerMatchIndex.value = (viewerMatchIndex.value - 1 + pages.size) % pages.size
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

    suspend fun renderPage(
        uri: String,
        index: Int,
        widthPx: Int,
        password: String? = null,
    ): Bitmap? {
        return withContext(Dispatchers.IO) {
            rendererMutex.withLock {
                try {
                    ensureRendererLocked(uri, password)
                    val count = rendererSession?.pageCount() ?: 0
                    if (index !in 0 until count) return@withLock null
                    rendererSession?.renderPage(index, widthPx)
                } catch (_: OutOfMemoryError) {
                    null
                } catch (_: IndexOutOfBoundsException) {
                    null
                }
            }
        }
    }

    suspend fun getLinks(uri: String, index: Int, password: String? = null): List<MuLink> {
        return withContext(Dispatchers.IO) {
            rendererMutex.withLock {
                try {
                    ensureRendererLocked(uri, password)
                    val count = rendererSession?.pageCount() ?: 0
                    if (index !in 0 until count) return@withLock emptyList()
                    rendererSession?.getLinks(index).orEmpty()
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }
    }

    suspend fun getPageSize(uri: String, index: Int, password: String? = null): PointF? {
        return withContext(Dispatchers.IO) {
            rendererMutex.withLock {
                try {
                    ensureRendererLocked(uri, password)
                    val count = rendererSession?.pageCount() ?: 0
                    if (index !in 0 until count) return@withLock null
                    rendererSession?.pageSize(index)
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    suspend fun closeRendererSession() {
        withContext(Dispatchers.IO) {
            rendererMutex.withLock {
                rendererSession?.close()
                rendererSession = null
                rendererUri = null
                rendererPassword = null
                _pageCount.value = 0
            }
        }
    }

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

    private fun ensureRendererLocked(uri: String, password: String?) {
        if (rendererSession != null && rendererUri == uri && rendererPassword == password) return
        rendererSession?.close()
        val parsedUri = Uri.parse(uri)
        val pfd = getApplication<Application>().contentResolver.openFileDescriptor(parsedUri, "r")
            ?: throw IllegalStateException("Unable to open PDF.")
        val renderer = MuPdfPageRenderer()
        try {
            renderer.open(pfd, password)
        } catch (t: Throwable) {
            runCatching { pfd.close() }
            throw t
        }
        rendererSession = renderer
        rendererUri = uri
        rendererPassword = password
        _pageCount.value = renderer.pageCount()
    }

    override fun onCleared() {
        rendererSession?.close()
        rendererSession = null
        super.onCleared()
    }
}
