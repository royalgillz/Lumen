package com.lumen.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lumen.app.data.db.entity.PageEntity

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
}
