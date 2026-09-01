package com.lumen.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val filename: String,
    @ColumnInfo(defaultValue = "") val treeUri: String = "",
    val status: String = STATUS_PENDING,
    val pageCount: Int = 0,
    val lastModified: Long = 0L,
    val addedAt: Long = System.currentTimeMillis(),
    val indexedAt: Long? = null,
    val sizeBytes: Long = 0L,
    /** When the viewer last opened this document — user recency, distinct from
     *  indexedAt (system recency). Null until first opened. */
    val lastOpenedAt: Long? = null,
    /** Auto-extracted display title. Null = never attempted; "" = attempted,
     *  nothing trustworthy found (DocumentTitles.NONE). Recomputed on index. */
    val derivedTitle: String? = null,
    /** Embedded PDF metadata author (sanitized), null when absent. Written
     *  after extraction on every successful pass. */
    val author: String? = null,
    /** Null = permanent library document. Non-null = externally-opened doc
     *  indexed under a rolling TTL (epoch millis when its index rows expire).
     *  Ephemeral docs are search-visible but invisible to every library
     *  surface; the purge deletes them past expiry (bookmarks and reading
     *  positions, keyed by URI outside this table, survive). */
    val ephemeralExpiresAt: Long? = null,
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_INDEXING = "indexing"
        const val STATUS_INDEXED = "indexed"
        const val STATUS_ENCRYPTED = "encrypted"
        const val STATUS_ERROR = "error"
    }
}

/**
 * The re-index upsert's entity merge: the indexer owns extraction columns
 * (status, pageCount, derivedTitle, …) and must preserve identity and
 * user-recency columns from the existing row — the REPLACE strategy wipes
 * anything not threaded through (this once silently reset lastOpenedAt on
 * every rescan).
 */
// @spec LIB-TTL-009, LIB-TTL-012
fun mergeForReindex(existing: DocumentEntity?, fresh: DocumentEntity): DocumentEntity =
    if (existing == null) {
        fresh
    } else {
        fresh.copy(
            id = existing.id,
            addedAt = existing.addedAt,
            lastOpenedAt = existing.lastOpenedAt,
            // Extraction-owned, but the merge runs before extraction: carry the
            // last-known author and derived title so an interrupted, failed, or
            // timed-out pass doesn't blank them (a NULL derivedTitle would also
            // invite the startup backfill to stamp a worse guess over pages the
            // pass already deleted). A successful pass overwrites both with
            // fresh values.
            author = existing.author,
            derivedTitle = existing.derivedTitle,
            // Ownership state, not extraction state: the ephemeral TTL is
            // managed only by setEphemeralExpiry, the purge, and the
            // library-adoption paths that DELETE the doc-form row outright.
            // A re-index pass (the ephemeral background indexer included)
            // must neither promote an ephemeral doc nor demote a library one.
            // @spec LIB-EXT-003
            ephemeralExpiresAt = existing.ephemeralExpiresAt,
        )
    }
