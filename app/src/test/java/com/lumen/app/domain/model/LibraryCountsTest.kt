package com.lumen.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryCountsTest {

    // @spec LIB-CNT-002
    @Test
    fun allBucketsNonzero_oneSentence() {
        val counts = LibraryCounts(total = 313, indexed = 310, failed = 1, pending = 2)
        assertEquals("313 documents · 310 indexed · 1 failed · 2 pending", counts.summarySentence())
    }

    // @spec LIB-CNT-002
    @Test
    fun zeroBuckets_omitted() {
        val counts = LibraryCounts(total = 313, indexed = 312, failed = 1, pending = 0)
        assertEquals("313 documents · 312 indexed · 1 failed", counts.summarySentence())
    }

    // @spec LIB-CNT-003
    @Test
    fun everythingIndexed_collapsesToAllIndexed() {
        val counts = LibraryCounts(total = 309, indexed = 309, failed = 0, pending = 0)
        assertEquals("309 documents · all indexed", counts.summarySentence())
    }

    // @spec LIB-CNT-003
    @Test
    fun singleDocument_singularNoun() {
        val counts = LibraryCounts(total = 1, indexed = 1, failed = 0, pending = 0)
        assertEquals("1 document · all indexed", counts.summarySentence())
    }

    // @spec LIB-CNT-004
    @Test
    fun emptyLibrary_noSentence() {
        assertNull(LibraryCounts(total = 0, indexed = 0, failed = 0, pending = 0).summarySentence())
    }
}
