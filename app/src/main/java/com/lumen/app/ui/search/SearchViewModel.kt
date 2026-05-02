package com.lumen.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumen.app.domain.model.SearchResult
import com.lumen.app.domain.usecase.SearchUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchUseCase: SearchUseCase,
) : ViewModel() {

    val query = MutableStateFlow("")

    private val _results = MutableStateFlow<List<SearchResult>>(emptyList())
    val results: StateFlow<List<SearchResult>> = _results

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching

    init {
        @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
        query
            .debounce(200)
            .distinctUntilChanged()
            .mapLatest { q ->
                _isSearching.value = true
                try {
                    searchUseCase(q)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    emptyList()
                }
            }
            .onEach { results ->
                _results.value = results
                _isSearching.value = false
            }
            .launchIn(viewModelScope)
    }
}
