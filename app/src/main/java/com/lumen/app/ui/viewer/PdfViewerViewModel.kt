package com.lumen.app.ui.viewer

import android.app.Application
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
) : AndroidViewModel(application) {

    private companion object {
        /** Skip highlight computation for PDFs above this size — they OOM on
         *  mid-range devices even with MuPDF's incremental parser. */
        private const val MAX_HIGHLIGHT_PDF_BYTES = 50L * 1024L * 1024L
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
            _highlights.value = try {
                pdfHighlighter.findOnPage(parsedUri, pageIndex, keyword, pdfPassword)
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
