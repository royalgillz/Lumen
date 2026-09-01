package com.lumen.app.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

/**
 * The identity migration behind an on-device file rename: SAF URIs encode the
 * path, so a rename changes the document's URI and every URI-keyed store must
 * follow — in one transaction, preserving the row id so nothing re-indexes.
 */
@Dao
interface RenameDao {

    @Query("DELETE FROM documents WHERE uri = :uri")
    suspend fun deleteDocumentByUri(uri: String)

    @Query("DELETE FROM document_titles WHERE docUri IN (:uris)")
    suspend fun deleteTitles(uris: List<String>)

    @Query("DELETE FROM bookmarks WHERE docUri = :uri")
    suspend fun deleteBookmarksAt(uri: String)

    @Query("UPDATE bookmarks SET docUri = :newUri WHERE docUri = :oldUri")
    suspend fun rekeyBookmarks(oldUri: String, newUri: String)

    // Keep-access merge (never used by file renames): the new URI may already
    // hold this same document's bookmarks from an earlier session — pages it
    // has win the collision (their notes survive); only the old URI's
    // non-colliding bookmarks re-key.
    // @spec LIB-EXT-017
    @Query(
        """DELETE FROM bookmarks WHERE docUri = :oldUri
           AND pageNumber IN (SELECT pageNumber FROM bookmarks WHERE docUri = :newUri)"""
    )
    suspend fun deleteBookmarksCollidingAt(oldUri: String, newUri: String)

    // derivedTitle resets to the attempted sentinel: a stale derivation must
    // not shadow the name the user just gave the file.
    @Query("UPDATE documents SET uri = :newUri, filename = :newFilename, derivedTitle = '' WHERE uri = :oldUri")
    suspend fun rekeyDocument(oldUri: String, newUri: String, newFilename: String)

    @Query("UPDATE documents SET derivedTitle = '' WHERE uri = :uri")
    suspend fun resetDerivedTitle(uri: String)

    /**
     * Re-keys after a successful provider rename. Leftovers keyed by the new
     * URI are orphans of a file that previously occupied that path — purged
     * first so a dead file's title or bookmarks can never attach to (or
     * collide with) the renamed document.
     */
    // @spec LIB-REN-002
    @Transaction
    suspend fun applyFileRename(oldUri: String, newUri: String, newFilename: String) {
        deleteDocumentByUri(newUri)
        deleteTitles(listOf(oldUri, newUri))
        deleteBookmarksAt(newUri)
        rekeyBookmarks(oldUri, newUri)
        rekeyDocument(oldUri, newUri, newFilename)
    }

    /**
     * An in-place provider rename (the returned URI equals the old one): the
     * document's identity is unchanged, so only the stored filename moves and
     * the one-truth clearing applies — no re-key, and no orphan purge, which
     * keyed by the same URI would delete the document's own rows.
     */
    // @spec LIB-REN-011
    @Transaction
    suspend fun applyInPlaceRename(uri: String, newFilename: String) {
        deleteTitles(listOf(uri))
        rekeyDocument(uri, uri, newFilename)
    }

    /**
     * The same-name save: no provider call, no re-key — but the file name still
     * becomes the one truth (custom title gone, stale derivation reset).
     */
    // @spec LIB-REN-010
    @Transaction
    suspend fun applyOneTruthClear(uri: String) {
        deleteTitles(listOf(uri))
        resetDerivedTitle(uri)
    }
}
