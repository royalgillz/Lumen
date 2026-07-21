package com.lumen.app.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.lumen.app.data.db.dao.DocumentDao
import com.lumen.app.data.repository.LibraryRepository
import com.lumen.app.di.ApplicationScope
import com.lumen.app.domain.usecase.AddFolderUseCase
import com.lumen.app.domain.usecase.RemoveFolderUseCase
import com.lumen.app.worker.IndexWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val documentDao: DocumentDao,
    private val libraryRepository: LibraryRepository,
    private val addFolderUseCase: AddFolderUseCase,
    private val removeFolderUseCase: RemoveFolderUseCase,
    private val workManager: WorkManager,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    val folders: StateFlow<Set<Uri>> = libraryRepository.folders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    // flowOn: hasPermissionFor hits the content resolver's persisted-permission
    // list — keep that off the main thread the stateIn collector runs on.
    val lostPermissionFolders: StateFlow<Set<Uri>> = libraryRepository.folders
        .map { uris -> uris.filter { !libraryRepository.hasPermissionFor(it) }.toSet() }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun deleteIndex() {
        viewModelScope.launch { documentDao.deleteAll() }
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
