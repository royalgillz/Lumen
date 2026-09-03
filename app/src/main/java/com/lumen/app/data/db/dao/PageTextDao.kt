package com.lumen.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lumen.app.data.db.entity.PageTextEntity

@Dao
interface PageTextDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pageText: PageTextEntity)

    // Caller must sanitize :query first — bare `"` or `*` throws a SQLiteException.
    //
    // One row per matching PAGE (all tokens somewhere on the page). No ORDER BY:
    // candidates come back in rowid order and are ranked in Kotlin from the
    // matchinfo blob — ordering by filename here would bias the candidate cap
    // toward alphabetically-early documents.
    @Query("""
        SELECT t.pageId AS pageId, p.pageNumber, p.isOcr,
               matchinfo(page_text_fts, 'pcnalx') AS matchInfo,
               d.id AS docId, d.uri, d.filename, d.treeUri, d.indexedAt,
               d.derivedTitle, dt.title AS customTitle
        FROM page_text_fts
        JOIN page_text AS t ON page_text_fts.rowid = t.pageId
        JOIN pages     AS p ON t.pageId = p.id
        JOIN documents AS d ON p.docId = d.id
        LEFT JOIN document_titles AS dt ON dt.docUri = d.uri
        WHERE page_text_fts MATCH :query
          AND d.status = 'indexed'
          AND (:filterByFolder = 0 OR d.treeUri IN (:treeUris))
          AND (:ocrOnly = 0 OR p.isOcr = 1)
          AND (:minIndexedAt = 0 OR d.indexedAt >= :minIndexedAt)
        LIMIT :limit
    """)
    suspend fun searchPages(
        query: String,
        filterByFolder: Int,
        treeUris: List<String>,
        ocrOnly: Int,
        minIndexedAt: Long,
        limit: Int,
    ): List<PageSearchRow>

    // Page-0 text for title derivation (indexing recomputes; backfill fills NULLs).
    @Query("""
        SELECT t.text FROM page_text t
        JOIN pages p ON t.pageId = p.id
        WHERE p.docId = :docId AND p.pageNumber = 0
        LIMIT 1
    """)
    suspend fun pageZeroText(docId: Long): String?

    // Original text for the displayed rows only — snippets are built in Kotlin
    // from original text (the FTS column holds normalized text), and fetching
    // the whole candidate pool would move megabytes per keystroke.
    // @spec SEARCH-SNIP-003
    @Query("SELECT pageId, text FROM page_text WHERE pageId IN (:pageIds)")
    suspend fun textsForPages(pageIds: List<Long>): List<PageTextRow>

    @Query("""
        SELECT p.pageNumber
        FROM page_text_fts
        JOIN page_text AS t ON page_text_fts.rowid = t.pageId
        JOIN pages     AS p ON t.pageId = p.id
        JOIN documents AS d ON p.docId = d.id
        WHERE page_text_fts MATCH :query
          AND d.uri = :docUri
        ORDER BY p.pageNumber
    """)
    suspend fun searchPagesInDocument(query: String, docUri: String): List<Int>
}

data class PageSearchRow(
    val pageId: Long,
    val pageNumber: Int,
    val isOcr: Boolean,
    val matchInfo: ByteArray?,
    val docId: Long,
    val uri: String,
    val filename: String,
    val treeUri: String,
    val indexedAt: Long?,
    val derivedTitle: String?,
    val customTitle: String?,
)

data class PageTextRow(
    val pageId: Long,
    val text: String,
)
