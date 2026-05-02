package com.lumen.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumen.app.data.fs.SafRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val safRepository: SafRepository,
) : ViewModel() {

    fun markDone() {
        viewModelScope.launch { safRepository.markOnboardingDone() }
    }
}
