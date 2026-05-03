package com.lumen.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lumen.app.data.db.entity.PageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(page: PageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(pages: List<PageEntity>): List<Long>

    @Query("SELECT * FROM pages WHERE docId = :docId ORDER BY pageNumber")
    suspend fun getByDocument(docId: Long): List<PageEntity>

    @Query("DELETE FROM pages WHERE docId = :docId")
    suspend fun deleteByDocument(docId: Long)

    @Query("""
        SELECT COUNT(*) FROM pages p
        JOIN documents d ON p.docId = d.id
        WHERE d.status = 'indexed'
    """)
    fun observeTotalPages(): Flow<Int>

    @Query("""
        SELECT COALESCE(SUM(p.wordCount), 0) FROM pages p
        JOIN documents d ON p.docId = d.id
        WHERE d.status = 'indexed'
    """)
    fun observeTotalWords(): Flow<Int>

    @Query("""
        SELECT COUNT(*) FROM pages p
        JOIN documents d ON p.docId = d.id
        WHERE d.status = 'indexed' AND p.isOcr = 1
    """)
    fun observeOcrPages(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pages WHERE docId = :docId AND isOcr = 1")
    suspend fun getOcrPageCount(docId: Long): Int
}
