package com.lumen.app.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lumen.app.data.db.dao.DocumentDao
import com.lumen.app.data.db.entity.DocumentEntity
import com.lumen.app.data.fs.SafRepository
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
    private val safRepository: SafRepository,
    private val documentDao: DocumentDao,
    private val workManager: WorkManager,
) : ViewModel() {

    val documents: StateFlow<List<DocumentEntity>> = documentDao
        .observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val folders: StateFlow<Set<Uri>> = safRepository.folderUris
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** True while any IndexWorker is running — drives the indexing banner. */
    val isIndexing: StateFlow<Boolean> = workManager
        .getWorkInfosByTagFlow("index")
        .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun addFolder(treeUri: Uri) {
        viewModelScope.launch {
            safRepository.addFolder(treeUri)
            workManager.enqueueUniqueWork(
                "index_${treeUri}",
                ExistingWorkPolicy.REPLACE,
                IndexWorker.buildRequest(treeUri),
            )
        }
    }

    fun removeFolder(treeUri: Uri) {
        viewModelScope.launch {
            safRepository.removeFolder(treeUri)
        }
    }

    fun reindexFolder(treeUri: Uri) {
        workManager.enqueueUniqueWork(
            "index_${treeUri}",
            ExistingWorkPolicy.REPLACE,
            IndexWorker.buildRequest(treeUri),
        )
    }
}
