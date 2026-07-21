package com.lumen.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumen.app.data.fs.SafRepository
import com.lumen.app.domain.usecase.IndexLibraryUseCase
import com.lumen.app.ui.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val safRepository: SafRepository,
    private val indexLibraryUseCase: IndexLibraryUseCase,
) : ViewModel() {

    // null = still loading from DataStore; UI waits before composing the nav graph
    val startDestination: StateFlow<String?> = safRepository.hasCompletedOnboarding
        .map { done -> if (done) Screen.Search.route else Screen.Onboarding.route }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // Re-enqueue indexing for all saved folders at most once per 6 hours.
        // IndexWorker skips files whose lastModified hasn't changed, so this
        // only does real work when new or modified PDFs are present.
        // KEEP policy means an already-running index is never interrupted.
        viewModelScope.launch {
            val lastRescan = safRepository.lastAutoRescanAt.first()
            if (System.currentTimeMillis() - lastRescan > AUTO_RESCAN_INTERVAL_MS) {
                indexLibraryUseCase()
                safRepository.setLastAutoRescanAt(System.currentTimeMillis())
            }
        }
    }

    private companion object {
        const val AUTO_RESCAN_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}
