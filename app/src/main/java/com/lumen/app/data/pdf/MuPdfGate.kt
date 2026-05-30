package com.lumen.app.data.pdf

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Global serialisation gate for MuPDF rasterisation.
 *
 * Bitmap pixels rendered by MuPDF live in the native graphics heap, which
 * `largeHeap` does not bound — the real limit is total native RSS before the
 * low-memory killer reaps the process. The OOM is the sum of the standing bitmap
 * cache and every *transient* in-flight render. Without coordination, the viewer
 * render, in-document search, thumbnails, and indexing OCR can each render at the
 * same time and their native peaks stack.
 *
 * A single permit makes the in-flight native peak deterministic: at most one
 * render (≤ the per-render cap) exists at a time across the whole app. The audit
 * that justifies permit=1 (no nesting among the gated producers) is in the
 * memory-PR notes; if a future caller nests gated calls, raise [PERMITS] to 2 for
 * deadlock margin rather than removing the gate.
 *
 * Only the heavy producers (page → pixmap, page → structured text) are gated.
 * Cheap metadata calls (`pageSize`, `links`) are intentionally left ungated so
 * page-layout passes that query every page are not serialised behind a render.
 */
object MuPdfGate {

    /** Keep at 1 unless a nesting hazard is introduced (see class doc). */
    private const val PERMITS = 1

    private val semaphore = Semaphore(PERMITS)

    suspend fun <T> withRenderPermit(block: suspend () -> T): T =
        semaphore.withPermit { block() }
}
