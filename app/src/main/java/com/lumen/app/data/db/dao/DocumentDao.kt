package com.lumen.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lumen.app.data.db.entity.DocumentEntity
import kotlinx.coroutines.flow.Flow

/**
 * Ephemeral visibility split (DB v14, LIB-EXT-003): documents with a non-null
 * `ephemeralExpiresAt` are externally-opened docs indexed under a rolling TTL.
 * They are SEARCH-visible (content and filename lanes keep them — that is the
 * point of indexing them) but LIBRARY-invisible: every list, count, folder
 * stat, recents feed, and scan-scoped query here filters them out so canonical
 * counts (LIB-CNT-001) and folder machinery never see a document that belongs
 * to no folder. The per-query classification lives in
 * docs/intent/library/library-design.md.
 */
@Dao
interface DocumentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(doc: DocumentEntity): Long

    @Update
    suspend fun update(doc: DocumentEntity)

    // Library-facing: the Library/Documents lists never show ephemeral docs.
    // @spec LIB-EXT-003
    @Query("SELECT * FROM documents WHERE ephemeralExpiresAt IS NULL ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    // Identity lookups: id/URI-targeted, ephemeral-blind by design (the
    // ephemeral machinery itself needs them).
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

    // Exact match on the owning tree — a bare prefix match over document URIs
    // also hits sibling folders whose id extends this one (Reports vs
    // Reports2). Schema-3-era rows carry treeUri = '' and are matched by URI
    // prefix instead, bounded at the '/document/' segment: every child URI is
    // "<treeUri>/document/<docId>", and a sibling's URI diverges before that
    // boundary, so the suffix can never match across folders.
    // Ephemeral docs belong to no folder tree (treeUri = '', document-form
    // URIs), so neither arm can match them in practice — the explicit
    // exclusion makes folder removal structurally unable to touch them.
    // @spec LIB-IDX-001, LIB-EXT-003
    @Query(
        "DELETE FROM documents WHERE ephemeralExpiresAt IS NULL AND (treeUri = :treeUri " +
            "OR (treeUri = '' AND instr(uri, :treeUri || '/document/') = 1))"
    )
    suspend fun deleteByTreeUri(treeUri: String)

    // Canonical library counts: ephemeral docs are never "a document in your
    // library", so they must not inflate the reconciled numbers.
    // @spec LIB-EXT-003
    @Query("SELECT COUNT(*) FROM documents WHERE status = 'indexed' AND ephemeralExpiresAt IS NULL")
    suspend fun countIndexed(): Int

    // @spec LIB-EXT-003
    @Query("SELECT COUNT(*) FROM documents WHERE status = 'indexed' AND ephemeralExpiresAt IS NULL")
    fun observeIndexedCount(): Flow<Int>

    // Most recently OPENED documents, for the Search home screen. Status-blind:
    // user recency reflects what the user did, not what indexed — an encrypted
    // document the user opened appears (tapping re-prompts for its password).
    // Library rows only: an ephemeral doc's recency lives in external_opens —
    // including it here would double-list the same document.
    // @spec LIB-EXT-003
    @Query(
        "SELECT * FROM documents WHERE lastOpenedAt IS NOT NULL AND ephemeralExpiresAt IS NULL " +
            "ORDER BY lastOpenedAt DESC LIMIT :limit"
    )
    fun observeRecentlyOpened(limit: Int = 8): Flow<List<DocumentEntity>>

    // Update-by-URI; returns the number of rows updated — 0 means no LIBRARY
    // row exists and the caller records the open in external_opens instead.
    // Ephemeral rows are excluded so an externally-opened doc keeps its recency
    // in external_opens even after the TTL indexer created a documents row —
    // a hit here would silently drop it from the recents feed.
    // @spec LIB-EXT-003
    @Query("UPDATE documents SET lastOpenedAt = :openedAt WHERE uri = :uri AND ephemeralExpiresAt IS NULL")
    suspend fun markOpened(uri: String, openedAt: Long): Int

    // Per-folder index-health numbers for the Library card: file, page, and
    // OCR-page counts grouped by tree URI. LEFT JOIN so zero-page documents count.
    // Ephemeral docs (treeUri = '') would otherwise surface as a ghost
    // empty-string folder row.
    // @spec LIB-EXT-003
    @Query("""
        SELECT d.treeUri AS treeUri,
               COUNT(DISTINCT d.id) AS files,
               COUNT(p.id) AS pages,
               COALESCE(SUM(CASE WHEN p.isOcr = 1 THEN 1 ELSE 0 END), 0) AS ocrPages
        FROM documents d
        LEFT JOIN pages p ON p.docId = d.id
        WHERE d.ephemeralExpiresAt IS NULL
        GROUP BY d.treeUri
    """)
    fun observeFolderStats(): Flow<List<FolderStatsRow>>

    // The indexer's post-extraction write: derived title and metadata author
    // land together, both recomputed from the file just read.
    @Query("UPDATE documents SET derivedTitle = :title, author = :author WHERE id = :id")
    suspend fun updateDerivedTitleAndAuthor(id: Long, title: String, author: String?)

    // The backfill's write — conditional so it can only fill a never-attempted
    // row: a metadata-derived title the indexer wrote after the backfill took
    // its work list must never be overwritten by a page-0 guess.
    // @spec LIB-TTL-013
    @Query("UPDATE documents SET derivedTitle = :title WHERE id = :id AND derivedTitle IS NULL")
    suspend fun updateDerivedTitleIfUnset(id: Long, title: String)

    // Never-attempted rows only — the one-time title backfill's work list.
    // Ephemeral docs stay included: their titles serve search-result rows, and
    // the backfill reads only stored text (no file access, so no grant risk).
    @Query("SELECT id, filename FROM documents WHERE derivedTitle IS NULL")
    suspend fun docsNeedingTitles(): List<DocTitleCandidate>

    // Library retry surface: a folder-scan retry pass must never adopt
    // ephemeral docs — their lifecycle belongs to the TTL indexer.
    // @spec LIB-EXT-003
    @Query("SELECT * FROM documents WHERE (status = 'pending' OR status = 'error') AND ephemeralExpiresAt IS NULL")
    suspend fun getPendingOrError(): List<DocumentEntity>

    @Query("DELETE FROM documents")
    suspend fun deleteAll()

    // Token matching happens in Kotlin (SearchRepository): SQLite's lower() is
    // ASCII-only and instr() forces contiguous-phrase semantics, so the SQL only
    // narrows by status and folder filter.
    // Search-facing: ephemeral docs stay in the filename lane (that is the
    // point of indexing them); an active folder filter naturally excludes them
    // (treeUri = '' matches no chosen folder).
    // @spec LIB-EXT-003
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

    // Already ephemeral-safe: ephemeral docs carry treeUri = ''.
    @Query("SELECT DISTINCT treeUri FROM documents WHERE treeUri != ''")
    suspend fun distinctTreeUris(): List<String>

    // Scan scope for vanished-document cleanup (IndexWorker) and the viewer's
    // library membership walk. Ephemeral docs belong to no tree; the explicit
    // exclusion makes the cleanup structurally unable to delete them.
    // @spec LIB-EXT-003
    @Query("SELECT id, uri FROM documents WHERE treeUri = :treeUri AND ephemeralExpiresAt IS NULL")
    suspend fun idUrisByTreeUri(treeUri: String): List<DocIdUri>

    // --- Ephemeral lifecycle (External Document Access) ---

    // Rolling TTL: the TTL indexer stamps this at index time and refreshes it
    // on every subsequent open of a still-accessible external doc. Conditional
    // on the row still being ephemeral, and returns the affected count: a
    // purge can reap the row between a caller's read and this write — the
    // caller falls through to a fresh pass on 0 — and a permanent library row
    // can never be demoted into the purge's reach.
    // @spec LIB-EXT-003, LIB-EXT-004
    @Query(
        """UPDATE documents SET ephemeralExpiresAt = :expiresAt
           WHERE uri = :uri AND ephemeralExpiresAt IS NOT NULL"""
    )
    suspend fun setEphemeralExpiry(uri: String, expiresAt: Long): Int

    // TTL purge: deletes only expired ephemeral rows; pages and FTS rows
    // cascade with them. Bookmarks (URI-keyed, no FK) and DataStore reading
    // positions are exempt by construction — that exemption is a core promise.
    // Returns the number of documents purged.
    // @spec LIB-EXT-004
    @Query("DELETE FROM documents WHERE ephemeralExpiresAt IS NOT NULL AND ephemeralExpiresAt < :now")
    suspend fun purgeExpiredEphemeral(now: Long): Int
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
