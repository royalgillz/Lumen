package com.lumen.app.domain.model

import com.lumen.app.ui.common.quantity

/**
 * The canonical count model: one status-bucketed query over `documents` feeds
 * every displayed count. Invariant: total = indexed + failed + pending.
 * Displayed together, buckets read as one sentence so the relationship is
 * explicit — never as disconnected tiles the user must reconcile.
 */
// @spec LIB-CNT-001, LIB-CNT-002, LIB-CNT-003, LIB-CNT-004
data class LibraryCounts(
    val total: Int,
    val indexed: Int,
    val failed: Int,
    val pending: Int,
) {
    /**
     * "313 documents · 312 indexed · 1 failed" — zero buckets omitted;
     * "313 documents · all indexed" when nothing failed or is pending;
     * null when the library is empty (the empty state replaces the sentence).
     */
    fun summarySentence(): String? {
        if (total == 0) return null
        val parts = mutableListOf(quantity(total, "document"))
        if (failed == 0 && pending == 0) {
            parts.add("all indexed")
        } else {
            parts.add("$indexed indexed")
            if (failed > 0) parts.add("$failed failed")
            if (pending > 0) parts.add("$pending pending")
        }
        return parts.joinToString(" · ")
    }
}
