package com.lumen.app.domain.usecase

import com.lumen.app.data.db.FtsQuerySanitizer
import com.lumen.app.data.repository.SearchRepository
import com.lumen.app.domain.model.SearchResult
import javax.inject.Inject

class SearchUseCase @Inject constructor(
    private val searchRepository: SearchRepository,
) {
    suspend operator fun invoke(raw: String): List<SearchResult> {
        val trimmed = raw.trim()
        if (trimmed.length < 2) return emptyList()
        return searchRepository.search(FtsQuerySanitizer.sanitize(trimmed))
    }
}
