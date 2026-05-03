package com.lumen.app.ui.onboarding

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lumen.app.data.fs.SafRepository
import com.lumen.app.domain.usecase.AddFolderUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
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
) : ViewModel() {
    private val _selectedFolder = MutableStateFlow<Uri?>(null)
    val selectedFolder: StateFlow<Uri?> = _selectedFolder

    val isIndexing: StateFlow<Boolean> = workManager
        .getWorkInfosByTagFlow("index")
        .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setSelectedFolder(uri: Uri) {
        _selectedFolder.value = uri
    }

    fun addFolderAndStartIndexing(onDone: () -> Unit = {}) {
        val folder = _selectedFolder.value ?: return
        viewModelScope.launch {
            addFolderUseCase(folder)
            onDone()
        }
    }

    fun markDone() {
        viewModelScope.launch { safRepository.markOnboardingDone() }
    }
}
