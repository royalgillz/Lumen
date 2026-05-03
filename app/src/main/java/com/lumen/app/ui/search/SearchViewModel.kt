package com.lumen.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lumen.app.data.db.dao.DocumentDao
import com.lumen.app.domain.model.SearchResult
import com.lumen.app.domain.usecase.SearchUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchUseCase: SearchUseCase,
    private val documentDao: DocumentDao,
    private val workManager: WorkManager,
) : ViewModel() {

    val query = MutableStateFlow("")

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

    init {
        @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
        query
            .onEach { q ->
                _results.value = emptyList()
                _isTruncated.value = false
                if (q.trim().length >= 2) _isSearching.value = true
            }
            .debounce(200)
            .distinctUntilChanged()
            .mapLatest { q ->
                try {
                    searchUseCase(q)
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
    }
}
