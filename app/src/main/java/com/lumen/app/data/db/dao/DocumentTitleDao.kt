package com.lumen.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lumen.app.data.db.entity.DocumentTitleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentTitleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(title: DocumentTitleEntity)

    // Reset to automatic = the row disappears; resolution falls through to
    // derivedTitle / filename.
    @Query("DELETE FROM document_titles WHERE docUri = :docUri")
    suspend fun delete(docUri: String)

    // Consumed as a docUri → title map alongside bookmarkCounts.
    @Query("SELECT * FROM document_titles")
    fun observeAll(): Flow<List<DocumentTitleEntity>>

    @Query("SELECT * FROM document_titles")
    suspend fun getAll(): List<DocumentTitleEntity>

    @Query("SELECT title FROM document_titles WHERE docUri = :docUri")
    suspend fun getTitle(docUri: String): String?
}
