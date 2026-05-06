package com.lumen.app.data.repository

import android.net.Uri
import android.provider.DocumentsContract
import com.lumen.app.data.db.dao.DocumentDao
import com.lumen.app.data.db.dao.LineDao
import com.lumen.app.domain.model.SearchFilters
import com.lumen.app.domain.model.SearchResult
import com.lumen.app.domain.model.SortOrder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchRepository @Inject constructor(
    private val lineDao: LineDao,
    private val documentDao: DocumentDao,
) {
    data class Output(val results: List<SearchResult>, val isTruncated: Boolean)

    suspend fun search(
        rawQuery: String,
        sanitizedQuery: String,
        filters: SearchFilters = SearchFilters(),
    ): Output {
        val rows = lineDao.search(sanitizedQuery)

        val contentFiltered = rows.filter { row ->
            (filters.folderIds.isEmpty() || treeUriToFolderId(row.treeUri) in filters.folderIds) &&
                (!filters.ocrOnly || row.isOcr)
        }

        // Filename search complements content search. Skip when OCR-only filter is active.
        val filenameRows = if (filters.ocrOnly) {
            emptyList()
        } else {
            documentDao.searchByFilename(rawQuery)
                .filter { row -> filters.folderIds.isEmpty() || treeUriToFolderId(row.treeUri) in filters.folderIds }
                .filter { row -> contentFiltered.none { it.docId == row.id } }
        }

        val contentSorted = when (filters.sortOrder) {
            SortOrder.RELEVANCE -> contentFiltered
            SortOrder.FILENAME -> contentFiltered.sortedBy { it.filename.lowercase() }
            SortOrder.MOST_RECENT -> contentFiltered.sortedByDescending { it.indexedAt ?: 0L }
        }
        val filenameSorted = when (filters.sortOrder) {
            SortOrder.RELEVANCE, SortOrder.FILENAME -> filenameRows.sortedBy { it.filename.lowercase() }
            SortOrder.MOST_RECENT -> filenameRows.sortedByDescending { it.indexedAt ?: 0L }
        }

        val combined = buildList {
            addAll(contentSorted.map { row ->
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
                    isFilenameMatch = false,
                )
            })
            addAll(filenameSorted.map { row ->
                SearchResult(
                    // Negative synthetic IDs prevent key collisions with line IDs.
                    lineId = -row.id,
                    docId = row.id,
                    uri = row.uri,
                    filename = row.filename,
                    pageNumber = 0,
                    lineNumber = 0,
                    snippet = "Filename matches your query.",
                    isOcr = false,
                    folderName = treeUriToFolderName(row.treeUri),
                    isFilenameMatch = true,
                )
            })
        }

        val truncated = combined.size > 200
        val results = combined.take(200)
        return Output(results, truncated)
    }

    suspend fun searchPagesInDocument(sanitizedQuery: String, docUri: String): List<Int> =
        lineDao.searchPagesInDocument(sanitizedQuery, docUri)

    private fun treeUriToFolderName(treeUri: String): String {
        if (treeUri.isBlank()) return ""
        return try {
            val parsed = Uri.parse(treeUri)
            val docId = runCatching { DocumentsContract.getTreeDocumentId(parsed) }.getOrNull()
            if (!docId.isNullOrBlank()) {
                // docId is typically "primary:DCIM/Camera" or "SD1234-5678:Downloads"
                val path = docId.substringAfter(':').ifEmpty { docId }
                val name = path.substringAfterLast('/')
                if (name.isNotBlank()) return name
            }
            // Fallback: extract last meaningful segment from the URI path segments
            parsed.pathSegments
                .lastOrNull { it.isNotBlank() }
                ?.substringAfterLast(':')
                ?.substringAfterLast('/')
                ?.ifBlank { null }
                ?: ""
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
