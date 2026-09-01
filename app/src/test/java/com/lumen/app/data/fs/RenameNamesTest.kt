package com.lumen.app.data.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RenameNamesTest {

    // @spec LIB-REN-002
    @Test
    fun targetFilename_appendsPdfExtensionToSanitizedStem() {
        assertEquals("Visa Appointment.pdf", renameTargetFilename("Visa Appointment"))
        assertEquals("trimmed.pdf", renameTargetFilename("  trimmed  "))
    }

    // The user edits the stem only; a pasted name ending in .pdf must not
    // double the extension.
    // @spec LIB-REN-002
    @Test
    fun targetFilename_neverDoublesTheExtension() {
        assertEquals("report.pdf", renameTargetFilename("report.pdf"))
        assertEquals("REPORT.PDF", renameTargetFilename("REPORT.PDF"))
        assertEquals("v2.final.pdf", renameTargetFilename("v2.final"))
    }

    // @spec LIB-REN-010
    @Test
    fun targetFilename_rejectsEmptyAndSeparators() {
        assertNull(renameTargetFilename(""))
        assertNull(renameTargetFilename("   "))
        assertNull(renameTargetFilename(".pdf"))
        assertNull(renameTargetFilename("a/b"))
        assertNull(renameTargetFilename("a\\b"))
    }

    // @spec LIB-REN-010
    @Test
    fun sameStem_detectedCaseSensitively_extensionInsensitively() {
        assertTrue(isSameFilename("I-20 Gill", "I-20 Gill.pdf"))
        assertTrue(isSameFilename("I-20 Gill.pdf", "I-20 Gill.pdf"))
        assertFalse(isSameFilename("i-20 gill", "I-20 Gill.pdf"))
        assertFalse(isSameFilename("I-20 Gill v2", "I-20 Gill.pdf"))
    }

    // report.pdf → Report.pdf must go through the two-step rename; an exact
    // same-name save and a real stem change must not.
    // @spec LIB-REN-013
    @Test
    fun caseOnlyRename_detectedOnlyWhenStemsDifferByCaseAlone() {
        assertTrue(isCaseOnlyRename("Report", "report.pdf"))
        assertTrue(isCaseOnlyRename("i-20 GILL", "I-20 Gill.pdf"))
        assertFalse(isCaseOnlyRename("report", "report.pdf"))
        assertFalse(isCaseOnlyRename("Reports", "report.pdf"))
        assertFalse(isCaseOnlyRename("", "report.pdf"))
    }

    // @spec LIB-REN-013
    @Test
    fun caseRenameTempName_keepsPdfExtension_neverCollidesWithTarget() {
        val temp = caseRenameTempFilename("Report.pdf", 1234L)
        assertEquals("Report.rename-1234.pdf", temp)
        assertFalse(temp.equals("Report.pdf", ignoreCase = true))
    }

    // A scan finding a temp-suffixed file must recover the intended name; the
    // parser is the exact inverse of caseRenameTempFilename.
    // @spec LIB-REN-015
    @Test
    fun caseRenameTempTarget_roundTripsTheTempName() {
        assertEquals("Report.pdf", caseRenameTempTarget(caseRenameTempFilename("Report.pdf", 1234L)))
        assertEquals("I-20 Gill.pdf", caseRenameTempTarget(caseRenameTempFilename("I-20 Gill", 9L)))
        assertEquals("v2.final.pdf", caseRenameTempTarget("v2.final.rename-77.pdf"))
    }

    // @spec LIB-REN-015
    @Test
    fun caseRenameTempTarget_rejectsOrdinaryFilenames() {
        assertNull(caseRenameTempTarget("Report.pdf"))
        assertNull(caseRenameTempTarget("rename-123.pdf"))
        assertNull(caseRenameTempTarget("Report.rename-.pdf"))
        assertNull(caseRenameTempTarget("Report.rename-12.txt"))
    }

    // A nested temp name (a recovery that itself got interrupted) strips only
    // the outermost suffix, converging one step per scan.
    // @spec LIB-REN-015
    @Test
    fun caseRenameTempTarget_stripsOnlyTheOutermostSuffix() {
        assertEquals("a.rename-1.pdf", caseRenameTempTarget("a.rename-1.rename-2.pdf"))
    }

    // Stable-ID providers rename in place and hand back the original URI —
    // that outcome must route to the filename-only update, never the re-key.
    // @spec LIB-REN-011
    @Test
    fun inPlaceRename_detectedByStringEqualUris() {
        assertTrue(isInPlaceRename("content://tree/A/document/17", "content://tree/A/document/17"))
        assertFalse(
            isInPlaceRename(
                "content://tree/A/document/A%2Fold.pdf",
                "content://tree/A/document/A%2Fnew.pdf",
            )
        )
    }
}
