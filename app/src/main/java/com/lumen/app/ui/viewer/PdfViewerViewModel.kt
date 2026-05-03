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

    // ── Keyword highlights ────────────────────────────────────────────────────

    private val _highlights = MutableStateFlow<PdfHighlighter.PageHighlights?>(null)
    val highlights: StateFlow<PdfHighlighter.PageHighlights?> = _highlights

    private var highlightJob: Job? = null

    fun loadHighlights(uri: String, pageIndex: Int, keyword: String) {
        if (keyword.isBlank()) {
            clearHighlights()
            return
        }
        val parsedUri = try { Uri.parse(uri) } catch (_: Exception) { return }
        highlightJob?.cancel()
        highlightJob = viewModelScope.launch(Dispatchers.IO) {
            _highlights.value = null
            val stream = try {
                getApplication<Application>().contentResolver.openInputStream(parsedUri)
            } catch (_: Exception) { null } ?: return@launch
            _highlights.value = stream.use { pdfHighlighter.findOnPage(it, pageIndex, keyword) }
        }
    }

    fun clearHighlights() {
        highlightJob?.cancel()
        _highlights.value = null
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
}
