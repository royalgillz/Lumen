package com.lumen.app.ui.onboarding

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lumen.app.data.fs.SafRepository
import com.lumen.app.di.ApplicationScope
import com.lumen.app.domain.usecase.AddFolderUseCase
import com.lumen.app.worker.IndexWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val addFolderUseCase: AddFolderUseCase,
    private val workManager: WorkManager,
    private val safRepository: SafRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {
    private val _selectedFolder = MutableStateFlow<Uri?>(null)
    val selectedFolder: StateFlow<Uri?> = _selectedFolder

    val isIndexing: StateFlow<Boolean> = workManager
        .getWorkInfosByTagFlow("index")
        .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** (processed, total) from the running index worker, or null when idle /
     *  before the first progress report. Drives a determinate progress bar. */
    val indexingProgress: StateFlow<Pair<Int, Int>?> = workManager
        .getWorkInfosByTagFlow("index")
        .map { infos ->
            infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                ?.progress
                ?.let { data ->
                    val total = data.getInt(IndexWorker.KEY_TOTAL, 0)
                    if (total > 0) data.getInt(IndexWorker.KEY_PROGRESS, 0) to total else null
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setSelectedFolder(uri: Uri) {
        _selectedFolder.value = uri
    }

    // Application scope, not viewModelScope: finishing onboarding navigates with
    // popUpTo(inclusive), which clears this ViewModel and would cancel the folder
    // add / DataStore write mid-flight — the user's first folder silently vanished.
    fun addFolderAndStartIndexing(onDone: () -> Unit = {}) {
        val folder = _selectedFolder.value ?: return
        appScope.launch {
            addFolderUseCase(folder)
            onDone()
        }
    }

    fun markDone() {
        appScope.launch { safRepository.markOnboardingDone() }
    }
}
