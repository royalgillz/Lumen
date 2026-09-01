package com.lumen.app.ui.search

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lumen.app.data.db.dao.DocumentDao
import com.lumen.app.data.db.dao.DocumentTitleDao
import com.lumen.app.data.db.dao.ExternalOpenDao
import com.lumen.app.data.fs.ExternalAccessOffers
import com.lumen.app.data.fs.SafRepository
import com.lumen.app.data.repository.SearchRepository
import com.lumen.app.di.ApplicationScope
import com.lumen.app.domain.model.ExternalOpensGate
import com.lumen.app.domain.model.PendingSearch
import com.lumen.app.domain.model.RecentDocument
import com.lumen.app.domain.model.SearchFilters
import com.lumen.app.domain.model.SearchResult
import com.lumen.app.domain.model.SortOrder
import com.lumen.app.domain.model.mergeRecents
import com.lumen.app.domain.usecase.KeepExternalAccessUseCase
import com.lumen.app.domain.usecase.SearchUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val searchUseCase: SearchUseCase,
    private val documentDao: DocumentDao,
    documentTitleDao: DocumentTitleDao,
    private val externalOpenDao: ExternalOpenDao,
    private val workManager: WorkManager,
    private val safRepository: SafRepository,
    private val keepExternalAccess: KeepExternalAccessUseCase,
    // Keep-access and recents-removal write external_opens rows and grants —
    // they must run to completion even when the user leaves the screen
    // mid-round-trip (a cancelled keep-access would leak the taken grant).
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    val query = MutableStateFlow("")
    val filters = MutableStateFlow(SearchFilters())

    // Bumps to the consumed request's id when a launcher hand-off lands, so the
    // hosting screen can focus the search field; 0 = nothing arrived yet.
    private val _focusRequests = MutableStateFlow(0L)
    val focusRequests: StateFlow<Long> = _focusRequests

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
    // recency, not indexer recency; status-blind by design. Library opens and
    // external VIEW-intent opens merge into one recency-ordered list of 8.
    // @spec SEARCH-UI-004
    val recentDocuments: StateFlow<List<RecentDocument>> = combine(
        documentDao.observeRecentlyOpened(8),
        externalOpenDao.observeRecent(8),
    ) { library, external -> mergeRecents(library, external) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * docUri → "the keep-file re-pick applies" for the expired (access-lost)
     * external rows currently on the recents list — the fact that turns a row's
     * tap into the keep-access picker instead of a doomed open attempt.
     * Recomputed when the recents list or the library folders change; absent
     * key = normal open. Dismissal is deliberately not consulted: it silences
     * proactive offers, and a tap on an expired row is the user explicitly
     * seeking access.
     */
    // @spec SEARCH-UI-012
    val expiredKeepFile: StateFlow<Map<String, Boolean>> =
        combine(recentDocuments, safRepository.folderUris) { rows, _ -> rows }
            .map { rows ->
                rows.filterIsInstance<RecentDocument.External>()
                    .filter { it.accessLost }
                    .associate { it.docUri to keepFilePickAppliesFor(it.docUri) }
            }
            // Grant probing (binder) and coverage checks (DataStore) off main.
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private suspend fun keepFilePickAppliesFor(docUri: String): Boolean = runCatching {
        // v1.2 ships the keep-access surface off: expired rows keep their
        // honest caption and removal affordance, but never open a picker.
        // @spec LIB-EXT-021
        if (!com.lumen.app.domain.model.ExternalAccessFeature.OFFERS_ENABLED) return@runCatching false
        val offer = ExternalAccessOffers.decide(
            docUri = docUri,
            apiLevel = Build.VERSION.SDK_INT,
            hasPersistedGrant = safRepository.hasPersistedRead(Uri.parse(docUri)),
            coveredByLibraryTree = safRepository.anyLiveLibraryTreeCovers(docUri),
            offerDismissed = false,
        )
        keepFilePickApplies(offer)
    }.getOrDefault(false)

    /** Outcome of a keep-access picker round-trip started from a recents row. */
    sealed class KeepAccessOutcome {
        /** Access kept (possibly under a re-keyed or fresh URI) — open it. */
        data class Open(val uri: String, val displayName: String) : KeepAccessOutcome()
        data class Failed(val message: String) : KeepAccessOutcome()
    }

    private val _keepAccessOutcomes = MutableSharedFlow<KeepAccessOutcome>(extraBufferCapacity = 4)
    val keepAccessOutcomes: SharedFlow<KeepAccessOutcome> = _keepAccessOutcomes.asSharedFlow()

    /** Outcome of tapping a LIVE external recents row. */
    sealed class ExternalTap {
        data class Open(val uri: String, val displayName: String) : ExternalTap()
        data class NeedsKeepAccess(val uri: String) : ExternalTap()
    }

    private val _externalTaps = MutableSharedFlow<ExternalTap>(extraBufferCapacity = 4)
    val externalTaps: SharedFlow<ExternalTap> = _externalTaps.asSharedFlow()

    /**
     * A live-looking external row's transient grant may have died silently
     * since its last open. Probe readability first (an fd open, no bytes
     * read) so the tap never burns on the viewer's error screen: a dead grant
     * marks the row expired on the spot and hands back the keep-file picker
     * instead. Persisted rows skip the probe — the held grant is the proof.
     */
    // @spec SEARCH-UI-014
    fun onExternalRecentTap(docUri: String, displayName: String, persisted: Boolean) {
        viewModelScope.launch {
            val readable = persisted || withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openFileDescriptor(Uri.parse(docUri), "r")?.use { }
                    true
                }.getOrDefault(false)
            }
            if (readable) {
                _externalTaps.emit(ExternalTap.Open(docUri, displayName))
            } else {
                // The probe just proved the loss — record it so the row renders
                // expired from now on; app scope + gate like every other
                // external_opens write.
                appScope.launch(Dispatchers.IO) {
                    runCatching {
                        ExternalOpensGate.mutex.withLock { externalOpenDao.markAccessLost(docUri) }
                    }
                }
                _externalTaps.emit(ExternalTap.NeedsKeepAccess(docUri))
            }
        }
    }

    /**
     * ACTION_OPEN_DOCUMENT result of a row-tap keep-access re-pick. The use
     * case owns grant-taking, same/rekey/fresh classification, and the
     * ephemeral index kickoff; every success path resolves to "open this URI".
     * Its two failure messages are distinct by design (grant failure vs
     * post-grant record failure) and surface verbatim.
     */
    // @spec SEARCH-UI-012
    fun onKeepAccessPicked(originalUri: String, picked: Uri) {
        // Application scope: the grant is taken inside the use case, and a
        // navigation away mid-round-trip must not cancel the row writes that
        // make it discoverable (the use case rethrows cancellation by design).
        appScope.launch {
            when (val result = keepExternalAccess(originalUri, picked)) {
                is KeepExternalAccessUseCase.Result.Kept -> emitKeepAccessOpen(result.uri)
                is KeepExternalAccessUseCase.Result.Rekeyed -> emitKeepAccessOpen(result.newUri)
                is KeepExternalAccessUseCase.Result.DifferentDocument -> emitKeepAccessOpen(result.newUri)
                is KeepExternalAccessUseCase.Result.Failed ->
                    _keepAccessOutcomes.emit(KeepAccessOutcome.Failed(result.message))
            }
        }
    }

    private suspend fun emitKeepAccessOpen(uri: String) {
        val name = runCatching { externalOpenDao.getByUri(uri)?.displayName }.getOrNull() ?: "PDF"
        _keepAccessOutcomes.emit(KeepAccessOutcome.Open(uri, name))
    }

    /**
     * Long-press removal of an expired external recents row: the row goes,
     * any ephemeral index rows go with it (removal means "forget this
     * document" — its extracted text must not stay searchable for days), and
     * any persisted read grant still held for its URI is released — a deleted
     * row must never leave a grant undiscoverable (LIB-REC-007 discipline;
     * release is a no-op for the dead grants expired rows typically carry).
     * Gated: these are external_opens + grant writes, and must not interleave
     * with a viewer session's record/release for the same document.
     */
    // @spec SEARCH-UI-013
    fun removeExternalRecent(docUri: String) {
        appScope.launch(Dispatchers.IO) {
            runCatching {
                ExternalOpensGate.mutex.withLock {
                    externalOpenDao.delete(docUri)
                    documentDao.getByUri(docUri)?.let { doc ->
                        if (doc.ephemeralExpiresAt != null) documentDao.delete(doc.id)
                    }
                    safRepository.releasePersistedRead(docUri)
                }
            }
        }
    }

    /** docUri → user rename, for display titles on the recently-opened rows. */
    val customTitles: StateFlow<Map<String, String>> = documentTitleDao.observeAll()
        .map { rows -> rows.associate { it.docUri to it.title } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val availableFolders: StateFlow<Set<Uri>> = safRepository.folderUris
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    init {
        // Launcher hand-off (static shortcut, widget, ACTION_PROCESS_TEXT):
        // the request is sticky — each live instance applies it at most once
        // (per-instance handled id) and only while fresh, so a hidden instance
        // can never starve the visible one; every live surface converges on
        // the same query. An empty query means "just clear, ready to type".
        // @spec SEARCH-UI-008
        viewModelScope.launch {
            var lastHandledId = 0L
            PendingSearch.request.collect { request ->
                if (request != null && request.id != lastHandledId && PendingSearch.isFresh(request)) {
                    lastHandledId = request.id
                    query.value = request.query
                    _focusRequests.value = request.id
                }
            }
        }

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
