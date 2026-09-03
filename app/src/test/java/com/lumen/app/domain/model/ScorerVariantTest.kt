package com.lumen.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ScorerVariantTest {

    // @spec SEARCH-RANK-007
    @Test
    fun `stored enum names round-trip`() {
        assertEquals(ScorerVariant.BM25, ScorerVariant.fromPref("BM25"))
        assertEquals(ScorerVariant.CURRENT, ScorerVariant.fromPref("CURRENT"))
    }

    // @spec SEARCH-RANK-007
    @Test
    fun `unrecognized or absent values fall back to the production default`() {
        assertEquals(ScorerVariant.DEFAULT, ScorerVariant.fromPref("FUSED"))   // future value on old build
        assertEquals(ScorerVariant.DEFAULT, ScorerVariant.fromPref("garbage"))
        assertEquals(ScorerVariant.DEFAULT, ScorerVariant.fromPref(""))
        assertEquals(ScorerVariant.DEFAULT, ScorerVariant.fromPref(null))
        assertEquals(ScorerVariant.BM25, ScorerVariant.DEFAULT)
    }
}
