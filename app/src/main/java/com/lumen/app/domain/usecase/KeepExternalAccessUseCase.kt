package com.lumen.app.domain.usecase

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.lumen.app.data.db.LumenDatabase
import com.lumen.app.data.db.dao.DocumentDao
import com.lumen.app.data.db.dao.DocumentTitleDao
import com.lumen.app.data.db.dao.ExternalOpenDao
import com.lumen.app.data.db.dao.RenameDao
import com.lumen.app.data.db.entity.DocumentTitleEntity
import com.lumen.app.data.db.entity.ExternalOpenEntity
import com.lumen.app.data.fs.DocumentLocations
import com.lumen.app.data.fs.SafRepository
import com.lumen.app.domain.model.ExternalOpensGate
import kotlinx.coroutines.sync.withLock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Handles the ACTION_OPEN_DOCUMENT result of a per-file keep-access offer: the
 * user re-picked the document so Lumen can hold a persistable grant. The picked
 * URI is taken as a persisted read grant, then classified against the original
 * external URI:
 *
 *  - same URI: the grant lands on the row Lumen already has — mark it persisted
 *    and start the ephemeral index.
 *  - same provider document under a different URI (providers are free to hand a
 *    new URI form for the same file): every URI-keyed store MERGES onto the new
 *    URI — external-opens row (display name preserved, now persisted), bookmarks
 *    (pages the new URI already holds win the collision), custom title (moved
 *    only when the old URI has one), reading position (old wins; the new URI's
 *    survives when the old had none) — then the ephemeral index runs under the
 *    new URI. The new URI may already carry THIS document's own data from an
 *    earlier session, so nothing under it is ever purged wholesale.
 *  - a genuinely different document: recorded as a fresh persisted external open;
 *    the original row is not touched (its honest expired state stands).
 *
 * A pick that a library folder already covers is not an external document to
 * keep: no external row, no ephemeral index — the folder machinery owns it.
 * On a post-grant failure the taken grant is released again unless a persisted
 * row already claims it.
 */
// @spec LIB-EXT-017
class KeepExternalAccessUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: LumenDatabase,
    private val externalOpenDao: ExternalOpenDao,
    private val documentDao: DocumentDao,
    private val documentTitleDao: DocumentTitleDao,
    private val renameDao: RenameDao,
    private val safRepository: SafRepository,
    private val indexExternalDocument: IndexExternalDocumentUseCase,
) {

    sealed class Result {
        /** Grant kept on the original URI; the row is persisted now. */
        data class Kept(val uri: String) : Result()
        /** Same document, new URI — everything re-keyed to [newUri]. */
        data class Rekeyed(val newUri: String) : Result()
        /** The user picked a different file; recorded as a fresh open. */
        data class DifferentDocument(val newUri: String) : Result()
        data class Failed(val message: String) : Result()
    }

    suspend operator fun invoke(originalUri: String, pickedUri: Uri): Result =
        withContext(Dispatchers.IO) {
            val picked = pickedUri.toString()
            // All external_opens writes and paired grant operations serialize
            // through the process-wide gate — a live viewer session's record or
            // teardown release for the same document must never interleave.
            // The grant-take itself sits INSIDE the gate: taken outside, the
            // Delete-Index sweep could acquire the gate in between, release the
            // fresh grant, and the row writes below would then mint persisted=1
            // with no grant held. Callers must NOT hold the gate around this
            // call. Cancellation is rethrown, never mapped to Failed: the
            // callers run this on the application scope to completion, and a
            // swallowed cancellation after partial work would misreport
            // committed state.
            // @spec LIB-EXT-017
            try {
                ExternalOpensGate.mutex.withLock {
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            pickedUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    } catch (_: SecurityException) {
                        return@withLock Result.Failed("Couldn't keep access to this file.")
                    }
                    // Providers routinely hand a different id FORM for the same
                    // file on re-pick (Downloads "msf:" ids vs external-storage
                    // paths). A strict id mismatch whose display name matches
                    // the original row's is treated as the same document, so
                    // the expired row re-keys instead of leaving a duplicate
                    // that keeps re-opening the picker; the merge semantics
                    // make a rare false name match safe — nothing is purged.
                    // @spec LIB-EXT-017
                    val pick = classifyKeepAccessPick(originalUri, picked).let { base ->
                        if (base != KeepAccessPick.DIFFERENT_DOCUMENT) return@let base
                        val originalName = externalOpenDao.getByUri(originalUri)?.displayName
                        val pickedName = queryDisplayName(pickedUri)
                        if (originalName != null && pickedName != null &&
                            originalName.equals(pickedName, ignoreCase = true)
                        ) KeepAccessPick.SAME_DOCUMENT else base
                    }
                    // A pick a library folder covers is not an external document
                    // to keep: the folder pass owns its index, the library row
                    // its recency. The doc-form grant just taken is deliberately
                    // RETAINED — it is what makes the picked URI openable right
                    // now; the next open's library-identity reconciliation
                    // releases it once recency lands on the library row
                    // (VIEW-EXT-002).
                    // @spec LIB-EXT-017
                    if (safRepository.anyLiveLibraryTreeCovers(picked)) {
                        if (pick != KeepAccessPick.DIFFERENT_DOCUMENT) {
                            // The original external row's job is done — the
                            // library owns this document now; a leftover
                            // ephemeral index row would double-list search.
                            externalOpenDao.delete(originalUri)
                            documentDao.getByUri(originalUri)?.let { doc ->
                                if (doc.ephemeralExpiresAt != null) documentDao.delete(doc.id)
                            }
                        }
                        return@withLock Result.DifferentDocument(picked)
                    }
                    when (pick) {
                        KeepAccessPick.SAME_URI -> {
                            val row = ensureRow(originalUri)
                            externalOpenDao.markPersisted(originalUri)
                            indexExternalDocument(originalUri, grantPersists = true, displayName = row.displayName)
                            Result.Kept(originalUri)
                        }
                        KeepAccessPick.SAME_DOCUMENT -> {
                            val name = rekeyTo(originalUri, picked)
                            indexExternalDocument(picked, grantPersists = true, displayName = name)
                            Result.Rekeyed(picked)
                        }
                        KeepAccessPick.DIFFERENT_DOCUMENT -> {
                            val name = recordFreshOpen(picked)
                            indexExternalDocument(picked, grantPersists = true, displayName = name)
                            Result.DifferentDocument(picked)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Failed after the grant was taken: release it again unless a
                // committed row write already claims it (a rekey or markPersisted
                // that landed before a later step threw).
                runCatching {
                    if (externalOpenDao.getByUri(picked)?.persisted != true) {
                        safRepository.releasePersistedRead(picked)
                    }
                }
                Result.Failed("Couldn't finish keeping access — try opening the file again.")
            }
        }

    /** The row the grant now belongs to — recorded fresh when pruning removed
     *  it meanwhile, so the persisted grant is always discoverable through an
     *  external_opens row (the unrecorded-grant release must never revoke it). */
    private suspend fun ensureRow(uri: String): ExternalOpenEntity =
        externalOpenDao.getByUri(uri) ?: ExternalOpenEntity(
            docUri = uri,
            displayName = queryDisplayName(Uri.parse(uri)) ?: "PDF",
            lastOpenedAt = System.currentTimeMillis(),
            persisted = true,
        ).also { fresh ->
            externalOpenDao.recordOpen(fresh).forEach { safRepository.releasePersistedRead(it) }
        }

    /**
     * One transaction MERGES every URI-keyed store onto the picked URI. The
     * file-rename purge discipline does not apply here: in a rename, rows at
     * the new URI belonged to a dead file that previously owned that path — in
     * a keep-access re-pick, the new URI may hold THIS document's own bookmarks,
     * title, and reading position from an earlier session under that URI form,
     * and purging them would destroy the user's data. Colliding bookmark pages
     * keep the new URI's entry (and its note); the title moves only when the
     * old URI has one; the reading position merges after the transaction (a
     * DataStore hiccup there must not fail an already-committed rekey). Any
     * ephemeral documents row at the old URI is dropped rather than re-keyed —
     * the fresh pass under the new URI rebuilds it.
     *
     * When pruning removed the old row meanwhile, a fresh persisted row is
     * recorded at the new URI instead (the ensureRow rule: the grant just taken
     * must always be discoverable through an external_opens row).
     */
    // @spec LIB-EXT-017
    private suspend fun rekeyTo(oldUri: String, newUri: String): String {
        val old = database.withTransaction {
            val existing = externalOpenDao.getByUri(oldUri)
            // Recents row: delete + insert preserving display name and recency,
            // now persisted; accessLost clears — the grant in hand is proof.
            if (existing != null) {
                externalOpenDao.delete(oldUri)
                externalOpenDao.upsert(
                    existing.copy(docUri = newUri, persisted = true, accessLost = false)
                )
            }
            // Bookmarks: merge — new-URI pages win the collision, the rest
            // re-key.
            renameDao.deleteBookmarksCollidingAt(oldUri, newUri)
            renameDao.rekeyBookmarks(oldUri, newUri)
            // Custom title: move only when the old URI actually has one — never
            // wipe a title the new URI already carries for this same document.
            documentTitleDao.getTitle(oldUri)?.let { title ->
                documentTitleDao.delete(newUri)
                documentTitleDao.delete(oldUri)
                documentTitleDao.upsert(DocumentTitleEntity(docUri = newUri, title = title))
            }
            // Ephemeral index rows at the dead URI: unreachable by the new
            // pass, so drop now instead of leaving them to the TTL purge —
            // search must not double-list the document meanwhile.
            documentDao.getByUri(oldUri)?.let { doc ->
                if (doc.ephemeralExpiresAt != null) documentDao.delete(doc.id)
            }
            existing
        }
        try {
            safRepository.mergeLastPage(oldUri, newUri)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The DB rekey committed; a reading-position hiccup degrades to
            // losing the position, never to a Failed report of a done rekey.
        }
        return old?.displayName ?: recordFreshOpen(newUri)
    }

    /** A different document entirely: a normal persisted external open. */
    private suspend fun recordFreshOpen(uri: String): String {
        val name = queryDisplayName(Uri.parse(uri)) ?: "PDF"
        val pruned = externalOpenDao.recordOpen(
            ExternalOpenEntity(
                docUri = uri,
                displayName = name,
                lastOpenedAt = System.currentTimeMillis(),
                persisted = true,
            )
        )
        // @spec LIB-REC-007
        pruned.forEach { prunedUri ->
            safRepository.releasePersistedRead(prunedUri)
            // A pruned doc's ephemeral index rows must go with its row and
            // grant: search must not keep offering a document that can no
            // longer open (the tap would dead-end until the TTL purge).
            // @spec LIB-EXT-019
            documentDao.getByUri(prunedUri)?.let { doc ->
                if (doc.ephemeralExpiresAt != null) documentDao.delete(doc.id)
            }
        }
        return name
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0).takeIf { it.isNotBlank() } else null
        }
    }.getOrNull()
}

internal enum class KeepAccessPick { SAME_URI, SAME_DOCUMENT, DIFFERENT_DOCUMENT }

/**
 * Pure classification of a keep-access pick against the original external URI.
 * Provider-document identity is (authority, percent-decoded SAF document id) —
 * the same rule as ui/viewer's SafDocumentUris.sameDocument, built here on
 * data/fs's DocumentLocations primitives instead so the domain layer never
 * reaches into a UI package (decoding differences between the two helpers are
 * immaterial: both yield the decoded id with '+' kept literal).
 */
// @spec LIB-EXT-017
internal fun classifyKeepAccessPick(originalUri: String, pickedUri: String): KeepAccessPick {
    if (pickedUri == originalUri) return KeepAccessPick.SAME_URI
    val authority = DocumentLocations.authorityOf(originalUri)
        ?: return KeepAccessPick.DIFFERENT_DOCUMENT
    if (authority != DocumentLocations.authorityOf(pickedUri)) {
        return KeepAccessPick.DIFFERENT_DOCUMENT
    }
    val originalId = DocumentLocations.documentIdOf(originalUri)
        ?: return KeepAccessPick.DIFFERENT_DOCUMENT
    return if (originalId == DocumentLocations.documentIdOf(pickedUri)) {
        KeepAccessPick.SAME_DOCUMENT
    } else {
        KeepAccessPick.DIFFERENT_DOCUMENT
    }
}
