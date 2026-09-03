package com.lumen.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import androidx.work.await
import com.lumen.app.data.db.dao.DocumentDao
import com.lumen.app.data.db.dao.ExternalOpenDao
import com.lumen.app.data.fs.SafRepository
import com.lumen.app.data.repository.LibraryRepository
import com.lumen.app.di.ApplicationScope
import com.lumen.app.domain.model.ExternalOpensGate
import com.lumen.app.domain.model.ScorerVariant
import com.lumen.app.ui.navigation.NavLayoutMode
import com.lumen.app.ui.theme.ThemeMode
import com.lumen.app.domain.usecase.AddFolderUseCase
import com.lumen.app.domain.usecase.RemoveFolderUseCase
import com.lumen.app.worker.IndexWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val documentDao: DocumentDao,
    private val externalOpenDao: ExternalOpenDao,
    private val libraryRepository: LibraryRepository,
    private val addFolderUseCase: AddFolderUseCase,
    private val removeFolderUseCase: RemoveFolderUseCase,
    private val workManager: WorkManager,
    private val safRepository: SafRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    val folders: StateFlow<Set<Uri>> = libraryRepository.folders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    // Appearance choices; parsing applies the LIGHT / THREE_TAB defaults.
    // @spec SET-APPEAR-001, SET-APPEAR-002
    val themeMode: StateFlow<ThemeMode> = safRepository.themeMode
        .map { ThemeMode.fromPref(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.LIGHT)

    // No navLayout read flow here: the Navigation toggle's selected state comes
    // from the applied mode the navigation host passes down (NAV-010) — a flow
    // recreated mid-switch would replay a stale initial and snap the toggle back.

    // Debug-only ranking scorer (row rendered only in debug builds; release
    // builds ignore the preference entirely).
    // @spec SEARCH-RANK-007
    val scorerVariant: StateFlow<ScorerVariant> = safRepository.debugScorerVariant
        .map { ScorerVariant.fromPref(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScorerVariant.DEFAULT)

    fun setScorerVariant(variant: ScorerVariant) {
        viewModelScope.launch { safRepository.saveDebugScorerVariant(variant.name) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { safRepository.saveThemeMode(mode.name) }
    }

    fun setNavLayout(layout: NavLayoutMode) {
        viewModelScope.launch { safRepository.saveNavLayout(layout.name) }
    }

    // flowOn: hasPermissionFor hits the content resolver's persisted-permission
    // list — keep that off the main thread the stateIn collector runs on.
    val lostPermissionFolders: StateFlow<Set<Uri>> = libraryRepository.folders
        .map { uris -> uris.filter { !libraryRepository.hasPermissionFor(it) }.toSet() }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    // A privacy wipe: index rows AND recently-opened traces go — no lingering
    // access, no leftover names. Application scope (the removeFolder pattern):
    // leaving Settings must not cut the confirmed wipe short. Index work is
    // cancelled first, then awaited to a terminal state: the cancel Operation
    // completes when cancellation is dispatched, not when the worker's
    // in-flight Room write lands, so a delete issued right away could be
    // overtaken by that write.
    // @spec SET-DATA-001, LIB-REC-006
    fun deleteIndex() {
        appScope.launch {
            workManager.cancelAllWorkByTag(IndexWorker.TAG).await()
            val sawUnfinished = awaitIndexWorkFinished()
            // One gate hold around the whole wipe: a concurrent external-open
            // record or keep-access serializes entirely before or after it —
            // never between the grant release and the row delete, where it
            // could re-record a row whose grant this wipe just released.
            ExternalOpensGate.mutex.withLock {
                documentDao.deleteAll()
                // @spec LIB-REC-007
                releasePersistedDocumentGrants()
                externalOpenDao.deleteAll()
                // A worker that outlived the bounded wait may have committed
                // one last write between the deletes — sweep once more.
                if (sawUnfinished) documentDao.deleteAll()
            }
        }
    }

    /** Waits (bounded) for cancelled index work to actually stop; returns true
     *  when any work was still unfinished on the first check. */
    private suspend fun awaitIndexWorkFinished(): Boolean {
        var sawUnfinished = false
        withTimeoutOrNull(5_000) {
            workManager.getWorkInfosByTagFlow(IndexWorker.TAG)
                .first { infos ->
                    val unfinished = infos.any { !it.state.isFinished }
                    if (unfinished) sawUnfinished = true
                    !unfinished
                }
        } ?: run { sawUnfinished = true }
        return sawUnfinished
    }

    /** Releases every persisted non-tree DOCUMENT grant (external opens, plus
     *  any orphans older builds left behind) straight from the resolver's own
     *  list; library folder TREE grants are untouched. */
    private fun releasePersistedDocumentGrants() {
        val resolver = context.contentResolver
        resolver.persistedUriPermissions
            .filterNot { DocumentsContract.isTreeUri(it.uri) }
            .forEach { grant ->
                val flags =
                    (if (grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                        (if (grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                try {
                    resolver.releasePersistableUriPermission(grant.uri, flags)
                } catch (_: SecurityException) {
                }
            }
    }

    fun addFolder(treeUri: Uri) {
        viewModelScope.launch { addFolderUseCase(treeUri) }
    }

    /** Application scope: the removal (which releases the SAF permission) must
     *  complete even if the user leaves Settings immediately after confirming. */
    fun removeFolder(treeUri: Uri) {
        appScope.launch { removeFolderUseCase(treeUri) }
    }

    fun reindexFolder(treeUri: Uri) {
        workManager.enqueueUniqueWork(
            "index_$treeUri",
            ExistingWorkPolicy.REPLACE,
            // force = true so re-index actually re-processes already-indexed docs,
            // not just new/changed ones.
            IndexWorker.buildRequest(treeUri, force = true),
        )
    }
}
