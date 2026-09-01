package com.lumen.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.lumen.app.data.db.entity.ExternalOpenEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExternalOpenDao {

    companion object {
        /** The merged recents list caps at 8; older external rows can never surface. */
        const val KEEP = 8
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(open: ExternalOpenEntity)

    @Query("SELECT MAX(lastOpenedAt) FROM external_opens")
    suspend fun maxLastOpenedAt(): Long?

    // The REPLACE upsert in recordOpen rebuilds the row from the caller's
    // entity, so once-only state must be read back and threaded through.
    @Query("SELECT offerDismissed FROM external_opens WHERE docUri = :docUri")
    suspend fun offerDismissedFor(docUri: String): Boolean?

    // Row lookup for the access-loss split: the caller reads `persisted` to
    // choose delete (grant held — file gone) vs markAccessLost (grant expired),
    // and `offerDismissed`/`persisted` to decide whether an offer is due.
    // @spec LIB-EXT-001, LIB-EXT-002
    @Query("SELECT * FROM external_opens WHERE docUri = :docUri LIMIT 1")
    suspend fun getByUri(docUri: String): ExternalOpenEntity?

    // LIMIT -1 OFFSET n: everything past the n best-ranked rows. Eviction is
    // expired-first: an accessLost row holds no living access to protect, so
    // the cap must never evict a live row (releasing its kept grant) while a
    // newer expired row survives — live rows rank ahead of expired ones, and
    // newest-first within each band.
    // @spec LIB-EXT-002
    @Query(
        """SELECT docUri FROM external_opens
           ORDER BY accessLost ASC, lastOpenedAt DESC LIMIT -1 OFFSET :keep"""
    )
    suspend fun urisBeyond(keep: Int): List<String>

    @Query("DELETE FROM external_opens WHERE docUri IN (:uris)")
    suspend fun deleteUris(uris: List<String>)

    /**
     * Records an external open: upsert (a reopen bumps the timestamp in place)
     * and prune past the cap. Returns the pruned URIs so the caller can release
     * any persisted grants for them (LIB-REC-007). The stored timestamp is
     * clamped to sort strictly newest — a clock rollback must never make the
     * row being opened the "oldest" and prune it, releasing its live grant.
     *
     * The caller sets [ExternalOpenEntity.persisted] from a record-time check
     * of the resolver's persisted-permission list (LIB-EXT-001). A successful
     * open is the proof access works, so `accessLost` is cleared (the fresh
     * entity carries false); `offerDismissed` is once-only state and is
     * preserved from the existing row across the REPLACE.
     */
    // @spec LIB-REC-001, LIB-REC-008, LIB-EXT-001, LIB-EXT-005
    @Transaction
    suspend fun recordOpen(open: ExternalOpenEntity): List<String> {
        val floor = maxLastOpenedAt()
        val stamped = if (floor != null && open.lastOpenedAt <= floor) {
            open.copy(lastOpenedAt = floor + 1)
        } else {
            open
        }
        val dismissed = offerDismissedFor(open.docUri) ?: false
        upsert(stamped.copy(accessLost = false, offerDismissed = dismissed))
        val pruned = urisBeyond(KEEP)
        if (pruned.isNotEmpty()) deleteUris(pruned)
        return pruned
    }

    // @spec SEARCH-UI-004
    @Query("SELECT * FROM external_opens ORDER BY lastOpenedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = KEEP): Flow<List<ExternalOpenEntity>>

    // Access-loss self-heal, persisted rows only: a held grant that fails means
    // the file itself is gone, so the row deletes as before. The caller (viewer)
    // chooses between this and markAccessLost by the row's persisted flag.
    // @spec LIB-REC-005
    @Query("DELETE FROM external_opens WHERE docUri = :docUri")
    suspend fun delete(docUri: String)

    // Access-loss for non-persisted rows: the grant simply expired — the row is
    // kept and shown expired instead of silently vanishing. Cleared by the next
    // successful recordOpen.
    // @spec LIB-EXT-002
    @Query("UPDATE external_opens SET accessLost = 1 WHERE docUri = :docUri")
    suspend fun markAccessLost(docUri: String)

    // Keep-access succeeded in place: a persistable grant is now held. Holding
    // a grant is proof access works, so any stale expired mark clears too.
    // @spec LIB-EXT-001
    @Query("UPDATE external_opens SET persisted = 1, accessLost = 0 WHERE docUri = :docUri")
    suspend fun markPersisted(docUri: String)

    // Offers appear once, never nag — set-only; recordOpen preserves it.
    // @spec LIB-EXT-005
    @Query("UPDATE external_opens SET offerDismissed = 1 WHERE docUri = :docUri")
    suspend fun setOfferDismissed(docUri: String)

    /** Enumerated before a wipe so persisted grants can be released first. */
    @Query("SELECT docUri FROM external_opens")
    suspend fun getAllUris(): List<String>

    // @spec LIB-REC-006
    @Query("DELETE FROM external_opens")
    suspend fun deleteAll()
}
