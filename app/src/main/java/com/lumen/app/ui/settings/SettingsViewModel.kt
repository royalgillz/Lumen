package com.lumen.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumen.app.data.db.dao.DocumentDao
import com.lumen.app.data.db.dao.PageDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val documentDao: DocumentDao,
    pageDao: PageDao,
) : ViewModel() {
    val indexedCount: StateFlow<Int> = documentDao.observeIndexedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val totalPages: StateFlow<Int> = pageDao.observeTotalPages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val totalWords: StateFlow<Int> = pageDao.observeTotalWords()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val ocrPages: StateFlow<Int> = pageDao.observeOcrPages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun deleteIndex() {
        viewModelScope.launch { documentDao.deleteAll() }
    }
}
