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

    // Most recently indexed documents, for the Search home screen.
    @Query("SELECT * FROM documents WHERE status = 'indexed' ORDER BY indexedAt DESC LIMIT :limit")
    fun observeRecentlyIndexed(limit: Int = 8): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE status = 'pending' OR status = 'error'")
    suspend fun getPendingOrError(): List<DocumentEntity>

    @Query("DELETE FROM documents")
    suspend fun deleteAll()

    // Token matching happens in Kotlin (SearchRepository): SQLite's lower() is
    // ASCII-only and instr() forces contiguous-phrase semantics, so the SQL only
    // narrows by status and folder filter.
    @Query("SELECT id, uri, filename, treeUri, indexedAt FROM documents WHERE status = 'indexed' AND (:filterByFolder = 0 OR treeUri IN (:treeUris)) AND (:minIndexedAt = 0 OR indexedAt >= :minIndexedAt)")
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
)

data class DocIdUri(val id: Long, val uri: String)
