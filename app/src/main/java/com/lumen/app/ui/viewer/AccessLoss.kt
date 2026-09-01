package com.lumen.app.ui.viewer

import com.lumen.app.data.db.entity.ExternalOpenEntity
import java.io.FileNotFoundException

/**
 * True when an open failure means the document is no longer reachable (a
 * revoked or expired grant, a vanished file) rather than unreadable content —
 * only these self-heal an external recents entry. Parse and memory failures on
 * a readable file must never evict a row.
 */
// @spec LIB-REC-005
fun isAccessLoss(failure: Throwable): Boolean {
    var t: Throwable? = failure
    var depth = 0
    while (t != null && depth < 16) {
        if (t is SecurityException || t is FileNotFoundException) return true
        t = t.cause
        depth++
    }
    return false
}

/** What an access-loss failure does to the document's `external_opens` row. */
enum class AccessLossRowAction {
    /** Persisted row: a held grant that fails means the file itself is gone —
     *  the row self-heals by deletion (the original behavior). */
    DELETE_ROW,
    /** Transient row: the grant simply expired — keep the row and render it
     *  honestly as expired instead of silently vanishing. */
    MARK_EXPIRED,
    /** No row to act on (never recorded, or already pruned). */
    NONE,
}

/**
 * The persisted-flag split behind external self-heal: only rows whose grant
 * was verified persisted at record time may still be deleted on access loss;
 * transient rows are kept and marked expired. The dead grant itself — if one
 * is somehow held — is released by the caller in every case.
 */
// @spec LIB-REC-005, LIB-EXT-002
fun accessLossRowAction(row: ExternalOpenEntity?): AccessLossRowAction = when {
    row == null -> AccessLossRowAction.NONE
    row.persisted -> AccessLossRowAction.DELETE_ROW
    else -> AccessLossRowAction.MARK_EXPIRED
}
