package com.lumen.app.domain.model

/**
 * Ship switch for the external-access OFFER surface (make-permanent banner,
 * Lumen-pdfs guided flow, keep-access pickers, ephemeral background indexing).
 *
 * OFF for v1.2 (owner decision, 2026-09-01): on-device QA found the flows not
 * yet dependable across source apps — folder-jump intents land wrong, and
 * Android's transient VIEW grants still die the moment a second external PDF
 * arrives, which no recovery UI fully papers over. The machinery stays built,
 * specified, and tested; v1.3 revisits by flipping this flag.
 *
 * What stays ON regardless (correctness, not surface): grant-persistence
 * recording, the honest expired-recents rendering, the readability probe
 * before opening a live external row, redelivery retry, the grant-hygiene
 * sweeps, and every gate-serialized write discipline.
 */
// @spec LIB-EXT-021
object ExternalAccessFeature {
    const val OFFERS_ENABLED = false
}
