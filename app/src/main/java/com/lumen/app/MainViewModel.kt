package com.lumen.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumen.app.data.fs.SafRepository
import com.lumen.app.ui.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    safRepository: SafRepository,
) : ViewModel() {

    // null = still loading from DataStore; UI waits before composing the nav graph
    val startDestination: StateFlow<String?> = safRepository.hasCompletedOnboarding
        .map { done -> if (done) Screen.Search.route else Screen.Onboarding.route }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
