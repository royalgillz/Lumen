package com.lumen.app.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lumen.app.data.db.entity.DocumentEntity
import com.lumen.app.data.repository.LibraryRepository
import com.lumen.app.domain.usecase.AddFolderUseCase
import com.lumen.app.domain.usecase.RemoveFolderUseCase
import com.lumen.app.worker.IndexWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
) : ViewModel() {

    val documents: StateFlow<List<DocumentEntity>> = libraryRepository.documents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val folders: StateFlow<Set<Uri>> = libraryRepository.folders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val isIndexing: StateFlow<Boolean> = workManager
        .getWorkInfosByTagFlow("index")
        .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Folders whose SAF URI permission has been revoked (e.g. after a reboot on some devices)
    val lostPermissionFolders: StateFlow<Set<Uri>> = libraryRepository.folders
        .map { uris -> uris.filter { !libraryRepository.hasPermissionFor(it) }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun addFolder(treeUri: Uri) {
        viewModelScope.launch { addFolderUseCase(treeUri) }
    }

    fun removeFolder(treeUri: Uri) {
        viewModelScope.launch { removeFolderUseCase(treeUri) }
    }

    fun reindexFolder(treeUri: Uri) {
        workManager.enqueueUniqueWork(
            "index_$treeUri",
            ExistingWorkPolicy.REPLACE,
            IndexWorker.buildRequest(treeUri),
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
