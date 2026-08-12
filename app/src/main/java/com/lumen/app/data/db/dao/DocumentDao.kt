package com.lumen.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lumen.app.data.db.entity.DocumentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(doc: DocumentEntity): Long

    @Update
    suspend fun update(doc: DocumentEntity)

    @Query("SELECT * FROM documents ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getById(id: Long): DocumentEntity?

    @Query("SELECT * FROM documents WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): DocumentEntity?

    @Query("UPDATE documents SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("UPDATE documents SET status = :status, pageCount = :pageCount, indexedAt = :indexedAt WHERE id = :id")
    suspend fun markIndexed(id: Long, status: String, pageCount: Int, indexedAt: Long)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun delete(id: Long)

    // instr(uri, treeUri) = 1 is a safe "starts with" check that avoids LIKE wildcard collisions
    @Query("DELETE FROM documents WHERE instr(uri, :treeUri) = 1")
    suspend fun deleteByTreeUri(treeUri: String)

    @Query("SELECT COUNT(*) FROM documents WHERE status = 'indexed'")
    suspend fun countIndexed(): Int

    @Query("SELECT COUNT(*) FROM documents WHERE status = 'indexed'")
    fun observeIndexedCount(): Flow<Int>

    // Most recently OPENED documents, for the Search home screen. Status-blind:
    // user recency reflects what the user did, not what indexed — an encrypted
    // document the user opened appears (tapping re-prompts for its password).
    @Query("SELECT * FROM documents WHERE lastOpenedAt IS NOT NULL ORDER BY lastOpenedAt DESC LIMIT :limit")
    fun observeRecentlyOpened(limit: Int = 8): Flow<List<DocumentEntity>>

    // Update-by-URI that silently no-ops for unknown URIs (external VIEW-intent opens).
    @Query("UPDATE documents SET lastOpenedAt = :openedAt WHERE uri = :uri")
    suspend fun markOpened(uri: String, openedAt: Long)

    // Per-folder index-health numbers for the Library card: file, page, and
    // OCR-page counts grouped by tree URI. LEFT JOIN so zero-page documents count.
    @Query("""
        SELECT d.treeUri AS treeUri,
               COUNT(DISTINCT d.id) AS files,
               COUNT(p.id) AS pages,
               COALESCE(SUM(CASE WHEN p.isOcr = 1 THEN 1 ELSE 0 END), 0) AS ocrPages
        FROM documents d
        LEFT JOIN pages p ON p.docId = d.id
        GROUP BY d.treeUri
    """)
    fun observeFolderStats(): Flow<List<FolderStatsRow>>

    @Query("UPDATE documents SET derivedTitle = :title WHERE id = :id")
    suspend fun updateDerivedTitle(id: Long, title: String)

    // Never-attempted rows only — the one-time title backfill's work list.
    @Query("SELECT id, filename FROM documents WHERE derivedTitle IS NULL")
    suspend fun docsNeedingTitles(): List<DocTitleCandidate>

    @Query("SELECT * FROM documents WHERE status = 'pending' OR status = 'error'")
    suspend fun getPendingOrError(): List<DocumentEntity>

    @Query("DELETE FROM documents")
    suspend fun deleteAll()

    // Token matching happens in Kotlin (SearchRepository): SQLite's lower() is
    // ASCII-only and instr() forces contiguous-phrase semantics, so the SQL only
    // narrows by status and folder filter.
    @Query("""
        SELECT d.id, d.uri, d.filename, d.treeUri, d.indexedAt,
               d.derivedTitle, dt.title AS customTitle
        FROM documents d
        LEFT JOIN document_titles dt ON dt.docUri = d.uri
        WHERE d.status = 'indexed'
          AND (:filterByFolder = 0 OR d.treeUri IN (:treeUris))
          AND (:minIndexedAt = 0 OR d.indexedAt >= :minIndexedAt)
    """)
    suspend fun indexedFilenameRows(filterByFolder: Int, treeUris: List<String>, minIndexedAt: Long): List<FilenameSearchRow>

    @Query("SELECT DISTINCT treeUri FROM documents WHERE treeUri != ''")
    suspend fun distinctTreeUris(): List<String>

    @Query("SELECT id, uri FROM documents WHERE treeUri = :treeUri")
    suspend fun idUrisByTreeUri(treeUri: String): List<DocIdUri>
}

data class FilenameSearchRow(
    val id: Long,
    val uri: String,
    val filename: String,
    val treeUri: String,
    val indexedAt: Long?,
    val derivedTitle: String?,
    val customTitle: String?,
)

data class DocIdUri(val id: Long, val uri: String)

data class DocTitleCandidate(
    val id: Long,
    val filename: String,
)

data class FolderStatsRow(
    val treeUri: String,
    val files: Int,
    val pages: Int,
    val ocrPages: Int,
)
