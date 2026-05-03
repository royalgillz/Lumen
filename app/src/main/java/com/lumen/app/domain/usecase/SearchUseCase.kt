package com.lumen.app.domain.usecase

import com.lumen.app.data.db.FtsQuerySanitizer
import com.lumen.app.data.repository.SearchRepository
import javax.inject.Inject

class SearchUseCase @Inject constructor(
    private val searchRepository: SearchRepository,
) {
    suspend operator fun invoke(raw: String): SearchRepository.Output {
        val trimmed = raw.trim()
        if (trimmed.length < 2) return SearchRepository.Output(emptyList(), false)
        val query = FtsQuerySanitizer.sanitize(trimmed)
            ?: return SearchRepository.Output(emptyList(), false)
        return searchRepository.search(query)
    }
}
