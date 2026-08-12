package com.lumen.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IndexWarningLineTest {

    // @spec LIB-HLTH-002
    @Test
    fun homogeneousEncrypted_namesThePasswordCause() {
        assertEquals("1 file needs a password", indexWarningLine(encryptedCount = 1, errorCount = 0))
        assertEquals("3 files need a password", indexWarningLine(encryptedCount = 3, errorCount = 0))
    }

    // @spec LIB-HLTH-002
    @Test
    fun homogeneousError_namesTheIndexingFailure() {
        assertEquals("1 file failed to index", indexWarningLine(encryptedCount = 0, errorCount = 1))
        assertEquals("2 files failed to index", indexWarningLine(encryptedCount = 0, errorCount = 2))
    }

    // @spec LIB-HLTH-002
    @Test
    fun mixedCauses_genericAttentionLine() {
        assertEquals("3 files need attention", indexWarningLine(encryptedCount = 1, errorCount = 2))
    }

    // @spec LIB-HLTH-002
    @Test
    fun nothingFailed_noLine() {
        assertNull(indexWarningLine(encryptedCount = 0, errorCount = 0))
    }
}
