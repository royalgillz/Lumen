package com.lumen.app.ui.viewer

import com.lumen.app.data.db.entity.ExternalOpenEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class AccessLossRowActionTest {

    private fun row(persisted: Boolean, accessLost: Boolean = false) = ExternalOpenEntity(
        docUri = "content://com.provider/document/doc%3A1",
        displayName = "file.pdf",
        lastOpenedAt = 1_000L,
        persisted = persisted,
        accessLost = accessLost,
    )

    // A held grant that fails means the file itself is gone — the persisted
    // row keeps the original self-heal-by-deletion behavior.
    // @spec LIB-REC-005
    @Test
    fun persistedRow_deletes() {
        assertEquals(AccessLossRowAction.DELETE_ROW, accessLossRowAction(row(persisted = true)))
    }

    // A transient grant simply expired: the row is kept and rendered expired
    // instead of silently vanishing.
    // @spec LIB-EXT-002
    @Test
    fun transientRow_marksExpired() {
        assertEquals(AccessLossRowAction.MARK_EXPIRED, accessLossRowAction(row(persisted = false)))
    }

    // Re-failing an already-expired transient row stays a mark, not a delete —
    // repeated failed reopens never escalate into eviction.
    // @spec LIB-EXT-002
    @Test
    fun alreadyExpiredTransientRow_staysMarked() {
        assertEquals(
            AccessLossRowAction.MARK_EXPIRED,
            accessLossRowAction(row(persisted = false, accessLost = true)),
        )
    }

    // No row (never recorded, or pruned meanwhile): nothing to act on — the
    // caller still releases any grant somehow held for the URI.
    // @spec LIB-REC-005
    @Test
    fun missingRow_isNone() {
        assertEquals(AccessLossRowAction.NONE, accessLossRowAction(null))
    }
}
