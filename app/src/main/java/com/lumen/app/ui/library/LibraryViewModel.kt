package com.lumen.app.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lumen.app.data.db.dao.BookmarkDao
import com.lumen.app.data.db.dao.PageDao
import com.lumen.app.data.db.entity.BookmarkEntity
import com.lumen.app.data.db.entity.DocumentEntity
import com.lumen.app.data.repository.LibraryRepository
import com.lumen.app.di.ApplicationScope
import com.lumen.app.domain.usecase.AddFolderUseCase
import com.lumen.app.domain.usecase.RemoveFolderUseCase
import com.lumen.app.worker.IndexWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val addFolderUseCase: AddFolderUseCase,
    private val removeFolderUseCase: RemoveFolderUseCase,
    private val workManager: WorkManager,
    private val pageDao: PageDao,
    private val bookmarkDao: BookmarkDao,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    // Null until the first emission so the UI can tell "loading" from "empty"
    // and not flash the no-folders state at a populated library.
    val documents: StateFlow<List<DocumentEntity>?> = libraryRepository.documents
        .map<List<DocumentEntity>, List<DocumentEntity>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val folders: StateFlow<Set<Uri>?> = libraryRepository.folders
        .map<Set<Uri>, Set<Uri>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isIndexing: StateFlow<Boolean> = workManager
        .getWorkInfosByTagFlow("index")
        .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // flowOn: hasPermissionFor hits the content resolver's persisted-permission
    // list — keep that off the main thread the stateIn collector runs on.
    val lostPermissionFolders: StateFlow<Set<Uri>> = libraryRepository.folders
        .map { uris -> uris.filter { !libraryRepository.hasPermissionFor(it) }.toSet() }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    // Library stats
    val totalPages: StateFlow<Int> = pageDao.observeTotalPages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val totalWords: StateFlow<Int> = pageDao.observeTotalWords()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val ocrPages: StateFlow<Int> = pageDao.observeOcrPages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // Document detail sheet
    val selectedDocument = MutableStateFlow<DocumentEntity?>(null)

    private val _selectedDocOcrPages = MutableStateFlow(0)
    val selectedDocOcrPages: StateFlow<Int> = _selectedDocOcrPages

    @OptIn(ExperimentalCoroutinesApi::class)
    val selectedDocBookmarks: StateFlow<List<BookmarkEntity>> = selectedDocument
        .flatMapLatest { doc ->
            if (doc == null) flowOf(emptyList()) else bookmarkDao.observeForDocument(doc.uri)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            selectedDocument.collectLatest { doc ->
                _selectedDocOcrPages.value = if (doc != null) pageDao.getOcrPageCount(doc.id) else 0
            }
        }
    }

    fun showDocumentDetail(doc: DocumentEntity) { selectedDocument.value = doc }
    fun hideDocumentDetail() { selectedDocument.value = null }

    fun addFolder(treeUri: Uri) {
        viewModelScope.launch { addFolderUseCase(treeUri) }
    }

    // ── Deferred folder removal (Undo) ────────────────────────────────────────
    // The SAF permission is released on actual removal, so removal can't be
    // silently reversed — instead the folder is hidden while the Undo snackbar
    // shows and only removed for real when the snackbar times out.

    private val _pendingRemovals = MutableStateFlow<Set<Uri>>(emptySet())
    val pendingRemovals: StateFlow<Set<Uri>> = _pendingRemovals

    fun requestRemoveFolder(treeUri: Uri) {
        _pendingRemovals.value = _pendingRemovals.value + treeUri
    }

    fun undoRemoveFolder(treeUri: Uri) {
        _pendingRemovals.value = _pendingRemovals.value - treeUri
    }

    /** Application scope: the removal must complete even if the user leaves the
     *  screen the instant the snackbar expires. */
    fun commitRemoveFolder(treeUri: Uri) {
        _pendingRemovals.value = _pendingRemovals.value - treeUri
        appScope.launch { removeFolderUseCase(treeUri) }
    }

    fun reindexFolder(treeUri: Uri) {
        workManager.enqueueUniqueWork(
            "index_$treeUri",
            ExistingWorkPolicy.REPLACE,
            // force = true so re-index actually re-processes already-indexed docs
            // (e.g. to backfill OCR highlight boxes), not just new/changed ones.
            IndexWorker.buildRequest(treeUri, force = true),
        )
    }

    fun retryDocument(doc: DocumentEntity) {
        viewModelScope.launch {
            libraryRepository.resetDocumentStatus(doc.id)
            val treeUri = doc.treeUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) } ?: return@launch
            workManager.enqueueUniqueWork(
                "index_$treeUri",
                ExistingWorkPolicy.KEEP,
                IndexWorker.buildRequest(treeUri),
            )
        }
    }
}
