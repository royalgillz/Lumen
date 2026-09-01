package com.lumen.app.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A PDF opened via an external VIEW intent (mail attachment, file manager) —
 * user recency for documents that have no library row. Lives outside
 * `documents` so canonical counts (LIB-CNT-001) never see it.
 *
 * Access honesty (DB v14): [persisted] records whether Lumen actually holds a
 * persistable read grant for this URI — checked at record time, not assumed
 * from the intent flags. Rows whose access dies are not silently deleted:
 * a non-persisted row that fails to reopen is marked [accessLost] and shown
 * expired (LIB-EXT-002); only persisted rows still self-heal by deletion (a
 * held grant that fails means the file itself is gone).
 */
@Entity(tableName = "external_opens")
data class ExternalOpenEntity(
    @PrimaryKey val docUri: String,
    /** Provider display name, resolved while the access grant was live. */
    val displayName: String,
    val lastOpenedAt: Long,
    /** Lumen holds a persistable read grant for this URI (verified at record
     *  time against the resolver's persisted-permission list). */
    @ColumnInfo(defaultValue = "0") val persisted: Boolean = false,
    /** A reopen failed with access loss; the row renders expired instead of
     *  vanishing. Cleared by the next successful open. */
    @ColumnInfo(defaultValue = "0") val accessLost: Boolean = false,
    /** The make-permanent (keep-access / add-folder) offer was dismissed for
     *  this document. Offers appear once, never nag; survives reopens. */
    @ColumnInfo(defaultValue = "0") val offerDismissed: Boolean = false,
)
