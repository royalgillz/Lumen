package com.lumen.app.data.repository

import com.lumen.app.data.db.dao.LineDao
import com.lumen.app.domain.model.SearchResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchRepository @Inject constructor(
    private val lineDao: LineDao,
) {
    suspend fun search(sanitizedQuery: String): List<SearchResult> =
        lineDao.search(sanitizedQuery).map { row ->
            SearchResult(
                lineId = row.lineId,
                docId = row.docId,
                uri = row.uri,
                filename = row.filename,
                pageNumber = row.pageNumber,
                lineNumber = row.lineNumber,
                snippet = row.snippet,
                isOcr = row.isOcr,
            )
        }
}
