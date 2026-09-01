package com.lumen.app.ui.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException

class AccessLossTest {

    // @spec LIB-REC-005
    @Test
    fun securityException_isAccessLoss() {
        assertTrue(isAccessLoss(SecurityException("revoked grant")))
    }

    // @spec LIB-REC-005
    @Test
    fun fileNotFound_isAccessLoss() {
        assertTrue(isAccessLoss(FileNotFoundException("content://gone")))
    }

    // @spec LIB-REC-005
    @Test
    fun wrappedAccessLoss_isDetectedThroughCauseChain() {
        val wrapped = RuntimeException("open failed", SecurityException("revoked"))
        assertTrue(isAccessLoss(wrapped))

        val deep = IllegalStateException(RuntimeException(FileNotFoundException("gone")))
        assertTrue(isAccessLoss(deep))
    }

    // Parse/memory failures on a readable file must NOT self-heal the entry.
    // @spec LIB-REC-005
    @Test
    fun nonAccessFailures_areNotAccessLoss() {
        assertFalse(isAccessLoss(OutOfMemoryError()))
        assertFalse(isAccessLoss(IllegalStateException("corrupt xref table")))
        assertFalse(isAccessLoss(RuntimeException("cannot parse page tree")))
    }

    @Test
    fun cyclicCauseChain_terminates() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertFalse(isAccessLoss(a))
    }
}
