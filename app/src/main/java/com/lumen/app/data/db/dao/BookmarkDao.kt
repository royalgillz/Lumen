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
}

data class BookmarkDocCount(val docUri: String, val count: Int)
