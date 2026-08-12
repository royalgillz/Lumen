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
import com.lumen.app.worker.IndexWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
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

    // Per-folder index-health numbers (files · pages · OCR pages) for the card.
    val folderStats: StateFlow<List<FolderStatsRow>> = documentDao.observeFolderStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    fun addFolder(treeUri: Uri) {
        viewModelScope.launch { addFolderUseCase(treeUri) }
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
