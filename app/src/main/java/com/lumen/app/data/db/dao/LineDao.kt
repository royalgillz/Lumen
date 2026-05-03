package com.lumen.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lumen.app.data.db.entity.LineContentEntity

@Dao
interface LineDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(line: LineContentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(lines: List<LineContentEntity>)

    @Query("DELETE FROM lines WHERE pageId = :pageId")
    suspend fun deleteByPage(pageId: Long)

    // Caller must sanitize :query before calling — bare `"` or `*` will throw a SQLiteException.
    @Query("""
        SELECT l.id AS lineId, l.pageId, l.lineNumber,
               snippet(lines_fts, '<b>', '</b>', '...', 0, 18) AS snippet,
               p.pageNumber, p.isOcr,
               d.id AS docId, d.uri, d.filename, d.treeUri, d.indexedAt
        FROM lines_fts
        JOIN lines  AS l ON lines_fts.rowid = l.id
        JOIN pages  AS p ON l.pageId  = p.id
        JOIN documents AS d ON p.docId = d.id
        WHERE lines_fts MATCH :query
          AND d.status = 'indexed'
        ORDER BY d.filename, p.pageNumber, l.lineNumber
        LIMIT :limit
    """)
    suspend fun search(query: String, limit: Int = 201): List<SearchResultRow>

    @Query("""
        SELECT DISTINCT p.pageNumber
        FROM lines_fts
        JOIN lines  AS l ON lines_fts.rowid = l.id
        JOIN pages  AS p ON l.pageId  = p.id
        JOIN documents AS d ON p.docId = d.id
        WHERE lines_fts MATCH :query
          AND d.uri = :docUri
        ORDER BY p.pageNumber
    """)
    suspend fun searchPagesInDocument(query: String, docUri: String): List<Int>
}

data class SearchResultRow(
    val lineId: Long,
    val pageId: Long,
    val lineNumber: Int,
    val snippet: String,
    val pageNumber: Int,
    val isOcr: Boolean,
    val docId: Long,
    val uri: String,
    val filename: String,
    val treeUri: String,
    val indexedAt: Long?,
)
