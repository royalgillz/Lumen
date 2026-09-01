package com.lumen.app.ui.search

import com.lumen.app.data.fs.ExternalAccessOffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalRecentsTest {

    // ── keepFilePickApplies: offer → picker applicability ─────────────────────

    // The file re-pick is the applicable escape hatch for KEEP_FILE, and for
    // LUMEN_PDFS_FLOW too — the guided-move offer is never exclusive of it.
    // @spec SEARCH-UI-012
    @Test
    fun keepFilePick_appliesForKeepFileAndLumenPdfsFlow() {
        assertTrue(keepFilePickApplies(ExternalAccessOffer.KEEP_FILE))
        assertTrue(keepFilePickApplies(ExternalAccessOffer.LUMEN_PDFS_FLOW))
    }

    // NONE (access already fine) and ADD_FOLDER (folder-based hatch, owned by
    // the viewer's access-loss surface) keep the normal open attempt.
    // @spec SEARCH-UI-012
    @Test
    fun keepFilePick_doesNotApplyForNoneOrAddFolder() {
        assertFalse(keepFilePickApplies(ExternalAccessOffer.NONE))
        assertFalse(keepFilePickApplies(ExternalAccessOffer.ADD_FOLDER))
    }

    // ── externalRecentTapAction: the full 2×2 matrix ─────────────────────────

    // Live rows always open normally — even when a keep-file offer would apply,
    // the open works, so nothing may hijack it.
    // @spec SEARCH-UI-012
    @Test
    fun tap_liveRowsAlwaysOpen() {
        assertEquals(
            ExternalRecentTap.OPEN,
            externalRecentTapAction(accessLost = false, keepFilePickApplies = false),
        )
        assertEquals(
            ExternalRecentTap.OPEN,
            externalRecentTapAction(accessLost = false, keepFilePickApplies = true),
        )
    }

    // Expired + keep-file applies: the tap becomes the keep-access picker
    // instead of a doomed open attempt.
    // @spec SEARCH-UI-012
    @Test
    fun tap_expiredWithKeepFileOpensPicker() {
        assertEquals(
            ExternalRecentTap.KEEP_ACCESS_PICKER,
            externalRecentTapAction(accessLost = true, keepFilePickApplies = true),
        )
    }

    // Expired without a keep-file offer stays a normal open attempt — the
    // viewer's access-loss surface owns the folder-based escape hatches.
    // @spec SEARCH-UI-012
    @Test
    fun tap_expiredWithoutKeepFileStillOpens() {
        assertEquals(
            ExternalRecentTap.OPEN,
            externalRecentTapAction(accessLost = true, keepFilePickApplies = false),
        )
    }

    // ── externalRecentCaption: honest copy, never promising a missing tap ─────

    // @spec SEARCH-UI-011
    @Test
    fun caption_liveRowsKeepFromAnotherApp() {
        assertEquals("From another app", externalRecentCaption(accessLost = false, keepFilePickApplies = false))
        // keepFilePickApplies is irrelevant while the row is live.
        assertEquals("From another app", externalRecentCaption(accessLost = false, keepFilePickApplies = true))
    }

    // @spec SEARCH-UI-011
    @Test
    fun caption_expiredWithoutPickerPointsBackToSourceApp() {
        assertEquals(
            "Access expired — reopen it from the app you shared it from",
            externalRecentCaption(accessLost = true, keepFilePickApplies = false),
        )
    }

    // The picker copy appears exactly when the tap really opens the picker —
    // caption and tap action must agree for every input pair.
    // @spec SEARCH-UI-011, SEARCH-UI-012
    @Test
    fun caption_promisesPickerExactlyWhenTapOpensIt() {
        assertEquals(
            "Access expired — tap to pick this file again",
            externalRecentCaption(accessLost = true, keepFilePickApplies = true),
        )
        for (lost in listOf(true, false)) {
            for (applies in listOf(true, false)) {
                val promisesPicker =
                    externalRecentCaption(lost, applies) == "Access expired — tap to pick this file again"
                val opensPicker =
                    externalRecentTapAction(lost, applies) == ExternalRecentTap.KEEP_ACCESS_PICKER
                assertEquals(promisesPicker, opensPicker)
            }
        }
    }
}
