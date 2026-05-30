package com.lumen.app.ui.search

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lumen.app.data.db.dao.DocumentDao
import com.lumen.app.data.db.entity.DocumentEntity
import com.lumen.app.data.fs.SafRepository
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
import kotlinx.coroutines.flow.distinctUntilChanged
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

    val indexedCount: StateFlow<Int> = documentDao.observeIndexedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val isIndexing: StateFlow<Boolean> = workManager
        .getWorkInfosByTagFlow("index")
        .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val searchHistory: StateFlow<List<String>> = safRepository.searchHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Recently indexed documents, surfaced on the Search home screen.
    val recentDocuments: StateFlow<List<DocumentEntity>> = documentDao.observeRecentlyIndexed(8)
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

        @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
        combine(query, filters) { q, f -> q to f }
            .onEach { (q, _) ->
                _results.value = emptyList()
                _isTruncated.value = false
                if (q.trim().length >= 2) _isSearching.value = true
            }
            .debounce(200)
            .distinctUntilChanged()
            .mapLatest { (q, f) ->
                try {
                    searchUseCase(q, f)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    null
                }
            }
            .onEach { output ->
                _results.value = output?.results ?: emptyList()
                _isTruncated.value = output?.isTruncated ?: false
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
}
