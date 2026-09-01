package com.lumen.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lumen.app.data.db.entity.BookmarkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmarks WHERE docUri = :docUri ORDER BY pageNumber")
    fun observeForDocument(docUri: String): Flow<List<BookmarkEntity>>

    /** Per-document bookmark counts, for the Library filter and card badges. */
    @Query("SELECT docUri, COUNT(*) AS count FROM bookmarks GROUP BY docUri")
    fun observeCountsByDocument(): Flow<List<BookmarkDocCount>>

    @Query("SELECT * FROM bookmarks WHERE docUri = :docUri AND pageNumber = :page LIMIT 1")
    suspend fun getByPage(docUri: String, page: Int): BookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("UPDATE bookmarks SET note = :note WHERE id = :id")
    suspend fun updateNote(id: Long, note: String?)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: Long)

    // Notes live outside both FTS tables; search matches them in Kotlin
    // (SearchRepository), so the SQL only narrows to non-blank notes on
    // library documents passing the folder / indexed-within filters. Inner
    // join by design: a note on a never-indexed external open has no folder,
    // title, or docId to build a result row from.
    // @spec SEARCH-NOTE-001
    @Query("""
        SELECT b.id AS bookmarkId, b.docUri, b.pageNumber, b.note,
               d.id AS docId, d.filename, d.treeUri, d.indexedAt,
               d.derivedTitle, dt.title AS customTitle
        FROM bookmarks b
        JOIN documents d ON d.uri = b.docUri
        LEFT JOIN document_titles dt ON dt.docUri = d.uri
        WHERE b.note IS NOT NULL AND TRIM(b.note) != ''
          AND (:filterByFolder = 0 OR d.treeUri IN (:treeUris))
          AND (:minIndexedAt = 0 OR d.indexedAt >= :minIndexedAt)
    """)
    suspend fun noteSearchRows(filterByFolder: Int, treeUris: List<String>, minIndexedAt: Long): List<NoteSearchRow>
}

data class BookmarkDocCount(val docUri: String, val count: Int)

data class NoteSearchRow(
    val bookmarkId: Long,
    val docUri: String,
    val pageNumber: Int,
    val note: String,
    val docId: Long,
    val filename: String,
    val treeUri: String,
    val indexedAt: Long?,
    val derivedTitle: String?,
    val customTitle: String?,
)
