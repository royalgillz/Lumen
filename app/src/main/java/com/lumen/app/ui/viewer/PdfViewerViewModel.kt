package com.lumen.app.ui.viewer

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumen.app.data.pdf.PdfHighlighter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PdfViewerViewModel @Inject constructor(
    application: Application,
    private val pdfHighlighter: PdfHighlighter,
) : AndroidViewModel(application) {

    private val _highlights = MutableStateFlow<PdfHighlighter.PageHighlights?>(null)
    val highlights: StateFlow<PdfHighlighter.PageHighlights?> = _highlights

    private var highlightJob: Job? = null

    fun loadHighlights(uri: String, pageIndex: Int, keyword: String) {
        if (keyword.isBlank()) return
        val parsedUri = try { Uri.parse(uri) } catch (_: Exception) { return }
        highlightJob?.cancel()
        highlightJob = viewModelScope.launch(Dispatchers.IO) {
            _highlights.value = null
            val stream = try {
                getApplication<Application>().contentResolver.openInputStream(parsedUri)
            } catch (_: Exception) { null } ?: return@launch
            _highlights.value = stream.use { pdfHighlighter.findOnPage(it, pageIndex, keyword) }
        }
    }

    fun clearHighlights() {
        highlightJob?.cancel()
        _highlights.value = null
    }
}
