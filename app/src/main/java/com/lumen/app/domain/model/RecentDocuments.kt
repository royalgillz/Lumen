package com.lumen.app.domain.model

import com.lumen.app.data.db.entity.DocumentEntity
import com.lumen.app.data.db.entity.ExternalOpenEntity

/** One row of the merged Recently-opened list on the search home. */
sealed interface RecentDocument {
    val lastOpenedAt: Long

    /** Access-loss honesty: true only for external rows whose reopen failed
     *  with a dead grant — rendered expired, sorted after every live row.
     *  Library rows are never expired (their grant is held via the folder). */
    val expired: Boolean get() = false

    data class Library(val document: DocumentEntity) : RecentDocument {
        override val lastOpenedAt: Long get() = document.lastOpenedAt ?: 0L
    }

    data class External(
        val docUri: String,
        val displayName: String,
        override val lastOpenedAt: Long,
        /** Lumen holds a persistable read grant for this URI. */
        val persisted: Boolean = false,
        /** A reopen failed with access loss; shown expired, never hidden. */
        val accessLost: Boolean = false,
    ) : RecentDocument {
        override val expired: Boolean get() = accessLost
    }
}

/**
 * Merges library and external opens into one recency-ordered list. Expired
 * (access-lost) external rows sort after every live row — recency ordering is
 * a promise about what the user can reopen, and a dead entry must not push a
 * live one below the fold (under the cap, live rows win eviction). Within each
 * band, ties resolve library-first — the entry guaranteed openable (its SAF
 * grant is held via the folder, while an external grant may have expired).
 */
// @spec SEARCH-UI-004, LIB-EXT-002
fun mergeRecents(
    library: List<DocumentEntity>,
    external: List<ExternalOpenEntity>,
    limit: Int = 8,
): List<RecentDocument> {
    val lib = library.map { RecentDocument.Library(it) }
    val ext = external.map {
        RecentDocument.External(
            docUri = it.docUri,
            displayName = it.displayName,
            lastOpenedAt = it.lastOpenedAt,
            persisted = it.persisted,
            accessLost = it.accessLost,
        )
    }
    return (lib + ext)
        .sortedWith(
            compareBy<RecentDocument> { it.expired }
                .thenByDescending { it.lastOpenedAt }
                .thenBy { it is RecentDocument.External }
        )
        .take(limit)
}
