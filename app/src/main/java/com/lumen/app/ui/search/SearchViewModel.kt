package com.lumen.app.ui.search

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lumen.app.data.db.dao.DocumentDao
import com.lumen.app.data.db.entity.DocumentEntity
import com.lumen.app.data.fs.SafRepository
import com.lumen.app.data.repository.SearchRepository
import com.lumen.app.domain.model.SearchFilters
import com.lumen.app.domain.model.SearchResult
import com.lumen.app.domain.model.SortOrder
import com.lumen.app.domain.usecase.SearchUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchUseCase: SearchUseCase,
    private val documentDao: DocumentDao,
    private val workManager: WorkManager,
    private val safRepository: SafRepository,
) : ViewModel() {

    val query = MutableStateFlow("")
    val filters = MutableStateFlow(SearchFilters())

    private val _results = MutableStateFlow<List<SearchResult>>(emptyList())
    val results: StateFlow<List<SearchResult>> = _results

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching

    private val _isTruncated = MutableStateFlow(false)
    val isTruncated: StateFlow<Boolean> = _isTruncated

    private val _searchFailed = MutableStateFlow(false)
    val searchFailed: StateFlow<Boolean> = _searchFailed

    // Null until the first DB emission, so the UI can distinguish "still loading"
    // from a genuinely empty library and not flash the "Nothing indexed" state.
    val indexedCount: StateFlow<Int?> = documentDao.observeIndexedCount()
        .map<Int, Int?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isIndexing: StateFlow<Boolean> = workManager
        .getWorkInfosByTagFlow("index")
        .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val searchHistory: StateFlow<List<String>> = safRepository.searchHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Recently OPENED documents, surfaced on the Search home screen — user
    // recency, not indexer recency; status-blind by design.
    // @spec SEARCH-UI-004
    val recentDocuments: StateFlow<List<DocumentEntity>> = documentDao.observeRecentlyOpened(8)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val availableFolders: StateFlow<Set<Uri>> = safRepository.folderUris
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    init {
        // Restore persisted filter settings (folder filters not persisted — they can be stale)
        viewModelScope.launch {
            val ocrOnly = safRepository.savedFilterOcrOnly.first()
            val sortOrderStr = safRepository.savedFilterSortOrder.first()
            val sortOrder = runCatching { SortOrder.valueOf(sortOrderStr) }.getOrDefault(SortOrder.RELEVANCE)
            if (ocrOnly || sortOrder != SortOrder.RELEVANCE) {
                filters.value = filters.value.copy(ocrOnly = ocrOnly, sortOrder = sortOrder)
            }
        }

        // Shimmer turns on immediately while typing, but results are only replaced
        // when the debounced search lands — clearing them per keystroke caused
        // flicker, and combining the eager clear with distinctUntilChanged left the
        // screen stuck on an empty shimmer when a keystroke was reverted within the
        // debounce window (the duplicate emission was suppressed, so nothing ever
        // reset isSearching). No distinctUntilChanged: re-running an identical FTS
        // query is cheap and guarantees the searching flag always resolves.
        @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
        combine(query, filters) { q, f -> q.trim() to f }
            .onEach { (q, _) ->
                _isSearching.value = q.length >= 2
                // Editing the query dismisses a previous failure immediately —
                // showing "Search failed" over a query being corrected is noise.
                _searchFailed.value = false
            }
            .debounce(200)
            .mapLatest { (q, f) ->
                if (q.length < 2) {
                    SearchOutcome.Idle
                } else {
                    try {
                        SearchOutcome.Success(searchUseCase(q, f))
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        SearchOutcome.Error
                    }
                }
            }
            .onEach { outcome ->
                when (outcome) {
                    SearchOutcome.Idle -> {
                        _results.value = emptyList()
                        _isTruncated.value = false
                        _searchFailed.value = false
                    }
                    is SearchOutcome.Success -> {
                        _results.value = outcome.output.results
                        _isTruncated.value = outcome.output.isTruncated
                        _searchFailed.value = false
                    }
                    SearchOutcome.Error -> {
                        _results.value = emptyList()
                        _isTruncated.value = false
                        _searchFailed.value = true
                    }
                }
                _isSearching.value = false
            }
            .launchIn(viewModelScope)

        // Persist filter changes, skipping the initial default emission
        filters
            .drop(1)
            .onEach { f ->
                safRepository.saveFilterOcrOnly(f.ocrOnly)
                safRepository.saveFilterSortOrder(f.sortOrder.name)
            }
            .launchIn(viewModelScope)
    }

    fun onResultSelected(q: String) {
        viewModelScope.launch { safRepository.addToSearchHistory(q.trim()) }
    }

    fun removeHistoryItem(q: String) {
        viewModelScope.launch { safRepository.removeFromSearchHistory(q) }
    }

    fun clearHistory() {
        viewModelScope.launch { safRepository.clearSearchHistory() }
    }

    private sealed class SearchOutcome {
        data object Idle : SearchOutcome()
        data class Success(val output: SearchRepository.Output) : SearchOutcome()
        data object Error : SearchOutcome()
    }
}
