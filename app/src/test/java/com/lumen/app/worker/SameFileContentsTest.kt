package com.lumen.app.worker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// @spec LIB-IDX-003
class SameFileContentsTest {

    @Test
    fun matchingNonZeroLastModified_isUnchanged() {
        assertTrue(
            sameFileContents(
                existingLastModified = 1_000L, existingSize = 10L,
                scannedLastModified = 1_000L, scannedSize = 99L,
            )
        )
    }

    @Test
    fun differingNonZeroLastModified_isChanged() {
        assertFalse(
            sameFileContents(
                existingLastModified = 1_000L, existingSize = 10L,
                scannedLastModified = 2_000L, scannedSize = 10L,
            )
        )
    }

    // The bug: providers that never populate COLUMN_LAST_MODIFIED yield 0, and
    // 0 == 0 skipped modified files forever. A zero must never be trusted alone.
    @Test
    fun zeroLastModified_fallsBackToSize_matchSkips() {
        assertTrue(
            sameFileContents(
                existingLastModified = 0L, existingSize = 10L,
                scannedLastModified = 0L, scannedSize = 10L,
            )
        )
    }

    @Test
    fun zeroLastModified_fallsBackToSize_mismatchReindexes() {
        assertFalse(
            sameFileContents(
                existingLastModified = 0L, existingSize = 10L,
                scannedLastModified = 0L, scannedSize = 11L,
            )
        )
    }

    // Neither signal available: re-index rather than skip forever.
    @Test
    fun zeroLastModifiedAndZeroSize_reindexes() {
        assertFalse(
            sameFileContents(
                existingLastModified = 0L, existingSize = 0L,
                scannedLastModified = 0L, scannedSize = 0L,
            )
        )
    }

    // A stored zero against a real scanned timestamp must re-index too.
    @Test
    fun storedZeroAgainstRealTimestamp_reindexes() {
        assertFalse(
            sameFileContents(
                existingLastModified = 0L, existingSize = 10L,
                scannedLastModified = 1_000L, scannedSize = 10L,
            )
        )
    }
}
