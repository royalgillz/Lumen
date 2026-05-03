package com.lumen.app.data.repository

import android.net.Uri
import android.provider.DocumentsContract
import com.lumen.app.data.db.dao.LineDao
import com.lumen.app.domain.model.SearchFilters
import com.lumen.app.domain.model.SearchResult
import com.lumen.app.domain.model.SortOrder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchRepository @Inject constructor(
    private val lineDao: LineDao,
) {
    data class Output(val results: List<SearchResult>, val isTruncated: Boolean)

    suspend fun search(sanitizedQuery: String, filters: SearchFilters = SearchFilters()): Output {
        val rows = lineDao.search(sanitizedQuery)

        val filtered = rows.filter { row ->
            (filters.folderIds.isEmpty() || treeUriToFolderId(row.treeUri) in filters.folderIds) &&
                (!filters.ocrOnly || row.isOcr)
        }

        val sorted = when (filters.sortOrder) {
            SortOrder.RELEVANCE -> filtered
            SortOrder.FILENAME -> filtered.sortedBy { it.filename.lowercase() }
            SortOrder.MOST_RECENT -> filtered.sortedByDescending { it.indexedAt ?: 0L }
        }

        val truncated = sorted.size > 200
        val results = sorted.take(200).map { row ->
            SearchResult(
                lineId = row.lineId,
                docId = row.docId,
                uri = row.uri,
                filename = row.filename,
                pageNumber = row.pageNumber,
                lineNumber = row.lineNumber,
                snippet = row.snippet,
                isOcr = row.isOcr,
                folderName = treeUriToFolderName(row.treeUri),
            )
        }
        return Output(results, truncated)
    }

    suspend fun searchPagesInDocument(sanitizedQuery: String, docUri: String): List<Int> =
        lineDao.searchPagesInDocument(sanitizedQuery, docUri)

    private fun treeUriToFolderName(treeUri: String): String {
        if (treeUri.isBlank()) return ""
        return try {
            val docId = DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))
            val path = docId.substringAfter(':')
            path.substringAfterLast('/').ifEmpty { path }
        } catch (_: Exception) {
            ""
        }
    }

    private fun treeUriToFolderId(treeUri: String): String {
        if (treeUri.isBlank()) return ""
        return runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(treeUri)) }
            .getOrDefault(treeUri)
    }
}
