package com.lumen.app.domain.usecase

import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.lumen.app.data.db.dao.DocumentDao
import com.lumen.app.data.db.entity.DocumentEntity
import com.lumen.app.worker.IndexWorker
import javax.inject.Inject

/**
 * The rolling-TTL engine behind ephemeral external indexing: invoked on every
 * successful open of a persisted-grant external document (and after keep-access
 * succeeds). An already-indexed, still-ephemeral document just gets its 7-day
 * window restamped — no work request, an open stays instant; a document with no
 * live index rows (never indexed, or purged after expiry, or a failed prior
 * pass) gets a single-document index enqueued. Transient-grant documents are
 * never indexed: the grant dies with the next external intent, and background
 * work against a dead grant can only fail.
 */
// @spec LIB-EXT-016
class IndexExternalDocumentUseCase @Inject constructor(
    private val workManager: WorkManager,
    private val documentDao: DocumentDao,
) {

    /**
     * @param grantPersists whether Lumen actually holds a persistable read
     *   grant for [docUri] (the caller's record-time check, LIB-EXT-001).
     *   Callers gate on it; passing false is a programming error.
     * @param displayName last-known provider display name, threaded to the
     *   worker as a fallback filename should the provider stop answering.
     */
    suspend operator fun invoke(docUri: String, grantPersists: Boolean, displayName: String? = null) {
        require(grantPersists) { "Ephemeral indexing is persisted-grant-only" }
        when (externalIndexAction(documentDao.getByUri(docUri))) {
            ExternalIndexAction.BUMP_TTL -> {
                // Conditional in the DAO: a purge can reap the row between the
                // read above and this write — 0 rows updated falls through to
                // a fresh pass instead of leaving the just-opened doc unindexed.
                // @spec LIB-EXT-004
                val bumped = documentDao.setEphemeralExpiry(
                    docUri,
                    System.currentTimeMillis() + IndexWorker.EPHEMERAL_TTL_MS,
                )
                if (bumped == 0) enqueue(docUri, displayName)
            }
            ExternalIndexAction.ENQUEUE -> enqueue(docUri, displayName)
            ExternalIndexAction.SKIP_LIBRARY_DOC -> Unit
        }
    }

    // Unique per URI with KEEP: rapid re-opens never stack a second pass
    // behind a running one (the worker restamps the TTL itself).
    private fun enqueue(docUri: String, displayName: String?) {
        workManager.enqueueUniqueWork(
            "index_external_$docUri",
            ExistingWorkPolicy.KEEP,
            IndexWorker.buildExternalRequest(Uri.parse(docUri), displayName),
        )
    }
}

enum class ExternalIndexAction {
    /** Indexed and alive: restamp `ephemeralExpiresAt` = now + 7 days, nothing else. */
    BUMP_TTL,
    /** No usable index rows: enqueue the single-document ephemeral pass. */
    ENQUEUE,
    /** A permanent library row owns this URI — the folder machinery's job, and
     *  stamping a TTL on it would demote it into the purge's reach. */
    SKIP_LIBRARY_DOC,
}

/**
 * Pure decision half of [IndexExternalDocumentUseCase]. An ephemeral row that
 * exists but never reached `indexed` (error, encrypted, interrupted mid-pass)
 * re-enqueues — the fresh pass restamps the TTL itself, so no bump here.
 */
// @spec LIB-EXT-016
internal fun externalIndexAction(existing: DocumentEntity?): ExternalIndexAction = when {
    existing == null -> ExternalIndexAction.ENQUEUE
    existing.ephemeralExpiresAt == null -> ExternalIndexAction.SKIP_LIBRARY_DOC
    existing.status == DocumentEntity.STATUS_INDEXED -> ExternalIndexAction.BUMP_TTL
    else -> ExternalIndexAction.ENQUEUE
}
