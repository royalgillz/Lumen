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
    fun deleteIndex() {
        viewModelScope.launch { documentDao.deleteAll() }
    }
}
