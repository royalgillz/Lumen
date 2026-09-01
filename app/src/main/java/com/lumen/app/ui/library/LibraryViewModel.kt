package com.lumen.app.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lumen.app.data.db.dao.BookmarkDao
import com.lumen.app.data.db.dao.DocumentDao
import com.lumen.app.data.db.dao.DocumentTitleDao
import com.lumen.app.data.db.dao.FolderStatsRow
import com.lumen.app.data.db.dao.PageDao
import com.lumen.app.data.db.entity.BookmarkEntity
import com.lumen.app.data.db.entity.DocumentEntity
import com.lumen.app.data.db.entity.DocumentTitleEntity
import com.lumen.app.data.fs.SafRepository
import com.lumen.app.data.repository.LibraryRepository
import com.lumen.app.di.ApplicationScope
import com.lumen.app.domain.usecase.AddFolderUseCase
import com.lumen.app.domain.usecase.RenameDocumentFileUseCase
import com.lumen.app.worker.IndexWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
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
    private val workManager: WorkManager,
    private val pageDao: PageDao,
    private val documentDao: DocumentDao,
    private val bookmarkDao: BookmarkDao,
    private val documentTitleDao: DocumentTitleDao,
    private val safRepository: SafRepository,
    private val renameDocumentFileUseCase: RenameDocumentFileUseCase,
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

    // Unfinished, not just RUNNING: enqueued work starts within seconds and
    // its scan snapshots pre-rename URIs — it must gate a rename the same way.
    val isIndexing: StateFlow<Boolean> = workManager
        .getWorkInfosByTagFlow("index")
        .map { infos -> infos.any { !it.state.isFinished } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // flowOn: hasPermissionFor hits the content resolver's persisted-permission
    // list — keep that off the main thread the stateIn collector runs on.
    val lostPermissionFolders: StateFlow<Set<Uri>> = libraryRepository.folders
        .map { uris -> uris.filter { !libraryRepository.hasPermissionFor(it) }.toSet() }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    // Per-folder index-health numbers (files · pages · OCR pages) for the card.
    val folderStats: StateFlow<List<FolderStatsRow>> = documentDao.observeFolderStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Raw-keyed reading positions (one bulk DataStore decode) for the progress
     *  lines on library and recently-opened rows; lookup via `lastPageFor`. */
    // @spec LIB-PRG-002
    val lastPages: StateFlow<Map<String, Int>> = safRepository.lastPages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // Library list ordering; persisted choice restored at startup.
    // @spec LIB-SORT-002
    val sortOrder = MutableStateFlow(LibrarySortOrder.RECENTLY_ADDED)

    fun setSortOrder(order: LibrarySortOrder) {
        sortOrder.value = order
        viewModelScope.launch { safRepository.saveLibrarySortOrder(order.name) }
    }

    fun reindexFolder(treeUri: String) {
        val parsed = treeUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) } ?: return
        workManager.enqueueUniqueWork(
            "index_$parsed",
            ExistingWorkPolicy.KEEP,
            IndexWorker.buildRequest(parsed),
        )
    }

    // Document detail sheet
    val selectedDocument = MutableStateFlow<DocumentEntity?>(null)

    private val _selectedDocOcrPages = MutableStateFlow(0)
    val selectedDocOcrPages: StateFlow<Int> = _selectedDocOcrPages

    /** docUri → bookmark count, driving the Library's bookmarked filter + badges. */
    val bookmarkCounts: StateFlow<Map<String, Int>> = bookmarkDao.observeCountsByDocument()
        .map { rows -> rows.associate { it.docUri to it.count } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** docUri → user rename, resolved ahead of derived titles and filenames. */
    val customTitles: StateFlow<Map<String, String>> = documentTitleDao.observeAll()
        .map { rows -> rows.associate { it.docUri to it.title } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Blank or null resets to automatic (deletes the row); input capped at 120
     *  chars to match the derivation gate. App scope: the write must land even
     *  if the user leaves the screen immediately.
     */
    // @spec LIB-TTL-004, LIB-TTL-005
    fun renameDocument(docUri: String, title: String?) {
        appScope.launch {
            val trimmed = title?.trim()?.take(120)
            if (trimmed.isNullOrEmpty()) {
                documentTitleDao.delete(docUri)
            } else {
                documentTitleDao.upsert(DocumentTitleEntity(docUri = docUri, title = trimmed))
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val selectedDocBookmarks: StateFlow<List<BookmarkEntity>> = selectedDocument
        .flatMapLatest { doc ->
            if (doc == null) flowOf(emptyList()) else bookmarkDao.observeForDocument(doc.uri)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            val saved = safRepository.librarySortOrder.first()
            sortOrder.value = runCatching { LibrarySortOrder.valueOf(saved) }
                .getOrDefault(LibrarySortOrder.RECENTLY_ADDED)
        }
        viewModelScope.launch {
            selectedDocument.collectLatest { doc ->
                _selectedDocOcrPages.value = if (doc != null) pageDao.getOcrPageCount(doc.id) else 0
            }
        }
    }

    fun showDocumentDetail(doc: DocumentEntity) { selectedDocument.value = doc }
    fun hideDocumentDetail() { selectedDocument.value = null }

    /** Detail sheet from surfaces that hold only a URI (search results, viewer). */
    // @spec LIB-REN-007
    fun showDocumentDetailByUri(uri: String) {
        viewModelScope.launch {
            documentDao.getByUri(uri)?.let { selectedDocument.value = it }
        }
    }

    // ── On-device file rename ─────────────────────────────────────────────────

    /** Why the "Also rename the file" toggle can or cannot act right now. */
    sealed class FileRenameGate {
        object Ready : FileRenameGate()
        /** Scan cleanup would delete a row re-keyed mid-pass. */
        object IndexingRunning : FileRenameGate()
        /** Folder granted read-only by an older build — needs a re-pick. */
        object NeedsWriteGrant : FileRenameGate()
        /** Provider doesn't advertise rename support for this document. */
        object Unsupported : FileRenameGate()
    }

    // Queries WorkManager directly rather than isIndexing: that stateIn flow
    // sits at its false initial value on hosts that never collect it (viewer,
    // merged Documents screen). Unfinished covers ENQUEUED/BLOCKED — one-time
    // index requests about to start would still scan pre-rename URIs and
    // delete the re-keyed row as vanished.
    // @spec LIB-REN-012
    private suspend fun indexingRunning(): Boolean = workManager
        .getWorkInfosByTagFlow("index")
        .first()
        .any { !it.state.isFinished }

    // @spec LIB-REN-004, LIB-REN-009
    suspend fun fileRenameGate(doc: DocumentEntity): FileRenameGate {
        if (indexingRunning()) return FileRenameGate.IndexingRunning
        val treeUri = doc.treeUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
            ?: return FileRenameGate.Unsupported
        if (!safRepository.hasWritePermission(treeUri)) return FileRenameGate.NeedsWriteGrant
        if (!renameDocumentFileUseCase.supportsRename(doc.uri)) return FileRenameGate.Unsupported
        return FileRenameGate.Ready
    }

    /** An on-device rename in flight (null [result]) or completed but not yet
     *  consumed. The rename runs on the app scope and its outcome is delivered
     *  here rather than to the launching composable: the dialog's composition
     *  scope dies on rotation, and the viewer's nav-entry replacement
     *  (LIB-REN-008) must still fire from the recreated host. */
    data class FileRenameDelivery(
        val docUri: String,
        val result: RenameDocumentFileUseCase.Result?,
    )

    private val _fileRename = MutableStateFlow<FileRenameDelivery?>(null)
    val fileRename: StateFlow<FileRenameDelivery?> = _fileRename.asStateFlow()

    /** Marks the current delivery handled; exactly one host per ViewModel (the
     *  document-sheet host) consumes, so results are never double-applied. */
    fun consumeFileRename() {
        _fileRename.value = null
    }

    /** App scope so the rename + re-key completes even if the user leaves the
     *  screen; the outcome lands in [fileRename]. No-op while one is in flight. */
    // @spec LIB-REN-002, LIB-REN-012
    fun startFileRename(doc: DocumentEntity, typedName: String) {
        val current = _fileRename.value
        if (current != null && current.result == null) return
        _fileRename.value = FileRenameDelivery(doc.uri, null)
        appScope.launch {
            // Re-checked at Save, not only at dialog-open: the 6-hour auto-rescan
            // can start while the dialog sits open, and its vanished-file cleanup
            // would delete the freshly re-keyed row.
            val result = if (indexingRunning()) {
                RenameDocumentFileUseCase.Result.Failed("Wait for indexing to finish.")
            } else {
                renameDocumentFileUseCase(doc, typedName)
            }
            _fileRename.value = FileRenameDelivery(doc.uri, result)
        }
    }

    fun addFolder(treeUri: Uri) {
        viewModelScope.launch { addFolderUseCase(treeUri) }
    }

    /** Awaitable add, for flows that must re-check grants right after (the
     *  rename dialog's re-grant path). */
    suspend fun addFolderAndWait(treeUri: Uri) = addFolderUseCase(treeUri)

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
