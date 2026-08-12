package com.lumen.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumen.app.data.db.dao.DocumentDao
import com.lumen.app.data.db.dao.PageTextDao
import com.lumen.app.data.fs.SafRepository
import com.lumen.app.domain.model.DocumentTitles
import kotlinx.coroutines.Dispatchers
import com.lumen.app.domain.usecase.IndexLibraryUseCase
import com.lumen.app.ui.navigation.NavLayoutMode
import com.lumen.app.ui.navigation.startDestinationFor
import com.lumen.app.ui.theme.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
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
    private val documentDao: DocumentDao,
    private val pageTextDao: PageTextDao,
) : ViewModel() {

    // null = still loading from DataStore; UI waits before composing anything.
    // Gating on the theme (not just the destination) means a dark-theme user
    // never sees a light first frame.
    // @spec SET-APPEAR-005
    val themeMode: StateFlow<ThemeMode?> = safRepository.themeMode
        .map<String?, ThemeMode?> { ThemeMode.fromPref(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // One object so the keyed NavHost never sees a mismatched layout/destination
    // pair mid-switch (two flows could emit at different instants).
    // @spec NAV-003
    data class NavConfig(val startDestination: String, val layout: NavLayoutMode)

    val navConfig: StateFlow<NavConfig?> =
        combine(safRepository.hasCompletedOnboarding, safRepository.navLayout) { done, layoutPref ->
            val layout = NavLayoutMode.fromPref(layoutPref)
            NavConfig(startDestinationFor(done, layout), layout)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // One-time title backfill for documents indexed before v11: derives from
        // stored page-0 text only (no PDF is opened). Self-gated — the NULL
        // column IS the "not yet attempted" flag, so this no-ops once done.
        // Racing IndexWorker is harmless: indexing recomputes from fresher data.
        // @spec LIB-TTL-008
        viewModelScope.launch(Dispatchers.IO) {
            documentDao.docsNeedingTitles().forEach { doc ->
                val derived = DocumentTitles.deriveTitle(
                    metadataTitle = null,
                    pageZeroText = pageTextDao.pageZeroText(doc.id),
                    filename = doc.filename,
                )
                documentDao.updateDerivedTitle(doc.id, derived)
            }
        }

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
