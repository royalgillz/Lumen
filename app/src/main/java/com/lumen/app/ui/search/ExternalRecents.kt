package com.lumen.app.ui.search

import com.lumen.app.data.fs.ExternalAccessOffer

/** What tapping an external recently-opened row does. */
enum class ExternalRecentTap {
    /** Normal open attempt through the viewer (live rows, and expired rows
     *  whose escape hatch is folder-based — the viewer's access-loss surface
     *  owns folder-adding). */
    OPEN,

    /** Single-file ACTION_OPEN_DOCUMENT re-pick instead of a doomed open —
     *  expired rows for which the keep-file offer applies. */
    KEEP_ACCESS_PICKER,
}

/**
 * Whether the per-file keep-access re-pick is the applicable escape hatch for
 * [offer]. LUMEN_PDFS_FLOW is never exclusive of the file re-pick (the offer
 * matrix presents them together), so both map to the picker; ADD_FOLDER and
 * NONE do not — those rows keep the normal open attempt.
 */
// @spec SEARCH-UI-012
fun keepFilePickApplies(offer: ExternalAccessOffer): Boolean =
    offer == ExternalAccessOffer.KEEP_FILE || offer == ExternalAccessOffer.LUMEN_PDFS_FLOW

/** Tap behavior of an external recents row from its two honest facts. */
// @spec SEARCH-UI-012
fun externalRecentTapAction(accessLost: Boolean, keepFilePickApplies: Boolean): ExternalRecentTap =
    if (accessLost && keepFilePickApplies) ExternalRecentTap.KEEP_ACCESS_PICKER
    else ExternalRecentTap.OPEN

/**
 * The caption under an external recently-opened row. Live rows keep the
 * established "From another app"; expired rows say honestly what happened and
 * what the tap will do — the picker variant only when the tap genuinely opens
 * the keep-access picker (SEARCH-UI-012), so the copy never promises an
 * affordance the row doesn't have.
 */
// @spec SEARCH-UI-011
fun externalRecentCaption(accessLost: Boolean, keepFilePickApplies: Boolean): String = when {
    !accessLost -> "From another app"
    keepFilePickApplies -> "Access expired — tap to pick this file again"
    else -> "Access expired — reopen it from the app you shared it from"
}
