package com.lumen.app.domain.usecase

import com.lumen.app.data.db.entity.DocumentEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rolling-TTL bump-vs-enqueue decision behind IndexExternalDocumentUseCase:
 * indexed-and-alive ephemeral rows only get their window restamped; anything
 * without usable index rows re-enqueues; permanent library rows are untouchable.
 */
class ExternalIndexActionTest {

    private fun doc(
        status: String,
        ephemeralExpiresAt: Long?,
        treeUri: String = "",
    ) = DocumentEntity(
        id = 7,
        uri = "content://com.android.externalstorage.documents/document/primary%3ADownload%2Fa.pdf",
        filename = "a.pdf",
        treeUri = treeUri,
        status = status,
        ephemeralExpiresAt = ephemeralExpiresAt,
    )

    // @spec LIB-EXT-016
    @Test
    fun noRow_enqueues() {
        assertEquals(ExternalIndexAction.ENQUEUE, externalIndexAction(null))
    }

    // @spec LIB-EXT-016
    @Test
    fun indexedEphemeralRow_bumpsTtlOnly() {
        assertEquals(
            ExternalIndexAction.BUMP_TTL,
            externalIndexAction(doc(DocumentEntity.STATUS_INDEXED, ephemeralExpiresAt = 123L)),
        )
    }

    // @spec LIB-EXT-016
    @Test
    fun expiredButUnpurgedIndexedRow_stillBumps() {
        // Presence of the row means the purge hasn't run; the doc was just
        // successfully opened, so the rolling window restarts either way.
        assertEquals(
            ExternalIndexAction.BUMP_TTL,
            externalIndexAction(doc(DocumentEntity.STATUS_INDEXED, ephemeralExpiresAt = 1L)),
        )
    }

    // @spec LIB-EXT-016
    @Test
    fun failedEphemeralRows_reEnqueue() {
        listOf(
            DocumentEntity.STATUS_ERROR,
            DocumentEntity.STATUS_ENCRYPTED,
            DocumentEntity.STATUS_PENDING,
            DocumentEntity.STATUS_INDEXING,
        ).forEach { status ->
            assertEquals(
                "status=$status",
                ExternalIndexAction.ENQUEUE,
                externalIndexAction(doc(status, ephemeralExpiresAt = 123L)),
            )
        }
    }

    // @spec LIB-EXT-016
    @Test
    fun permanentLibraryRow_isNeverTouched() {
        // A library document opened externally must not be TTL-stamped (that
        // would put it in the purge's reach) nor re-indexed outside its folder
        // pass — regardless of its status.
        listOf(
            DocumentEntity.STATUS_INDEXED,
            DocumentEntity.STATUS_ERROR,
            DocumentEntity.STATUS_ENCRYPTED,
        ).forEach { status ->
            assertEquals(
                "status=$status",
                ExternalIndexAction.SKIP_LIBRARY_DOC,
                externalIndexAction(
                    doc(status, ephemeralExpiresAt = null, treeUri = "content://tree/primary%3ADocs")
                ),
            )
        }
    }

    // @spec LIB-EXT-016
    @Test
    fun permanentRowWithoutTree_stillSkips() {
        // Null expiry alone decides ownership — a schema-3-era row with a blank
        // treeUri is still a permanent library document.
        assertEquals(
            ExternalIndexAction.SKIP_LIBRARY_DOC,
            externalIndexAction(doc(DocumentEntity.STATUS_INDEXED, ephemeralExpiresAt = null)),
        )
    }
}
