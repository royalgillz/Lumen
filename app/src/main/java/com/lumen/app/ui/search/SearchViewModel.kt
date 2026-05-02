package com.lumen.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumen.app.data.db.dao.LineDao
import com.lumen.app.domain.model.SearchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val lineDao: LineDao,
) : ViewModel() {

    val query = MutableStateFlow("")

    private val _results = MutableStateFlow<List<SearchResult>>(emptyList())
    val results: StateFlow<List<SearchResult>> = _results

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching

    init {
        @OptIn(FlowPreview::class)
        query
            .debounce(200)
            .distinctUntilChanged()
            .onEach { q -> runSearch(q) }
            .launchIn(viewModelScope)
    }

    private suspend fun runSearch(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.length < 2) {
            _results.value = emptyList()
            return
        }
        _isSearching.value = true
        val sanitized = sanitizeFtsQuery(trimmed)
        val rows = lineDao.search(sanitized)
        _results.value = rows.map { row ->
            SearchResult(
                lineId = row.lineId,
                docId = row.docId,
                uri = row.uri,
                filename = row.filename,
                pageNumber = row.pageNumber,
                lineNumber = row.lineNumber,
                snippet = row.snippet,
                bboxJson = row.bboxJson,
                isOcr = row.isOcr,
            )
        }
        _isSearching.value = false
    }

    private fun sanitizeFtsQuery(input: String): String {
        // Strip chars that break FTS4 MATCH syntax, then wrap in quotes for phrase search
        val cleaned = input.replace('"', ' ').replace('*', ' ').trim()
        return "\"$cleaned\""
    }
}
