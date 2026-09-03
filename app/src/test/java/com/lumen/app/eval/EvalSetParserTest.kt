package com.lumen.app.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** org.json is an Android platform class — Robolectric provides the real one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EvalSetParserTest {

    // @spec SEARCH-EVAL-002
    @Test
    fun `parses valid entries with optional fields`() {
        val parsed = EvalSetParser.parse(
            """
            [
              {"query": "student visa", "expectedFile": "I-20.pdf", "expectedPage": 1, "tag": "paraphrase"},
              {"query": "sevis", "expectedFile": "receipt.pdf"}
            ]
            """.trimIndent()
        )
        assertEquals(2, parsed.entries.size)
        assertTrue(parsed.invalid.isEmpty())

        val first = parsed.entries[0]
        assertEquals("student visa", first.query)
        assertEquals("I-20.pdf", first.expectedFile)
        assertEquals(1, first.expectedPage)
        assertEquals("paraphrase", first.tag)

        val second = parsed.entries[1]
        assertNull(second.expectedPage)          // document-level intent
        assertEquals("untagged", second.tag)
    }

    // @spec SEARCH-EVAL-002
    @Test
    fun `strips a UTF-8 BOM before parsing`() {
        val parsed = EvalSetParser.parse(
            "﻿[{\"query\": \"a b\", \"expectedFile\": \"x.pdf\"}]"
        )
        assertEquals(1, parsed.entries.size)
    }

    // @spec SEARCH-EVAL-002
    @Test
    fun `invalid entries are reported by index and reason while valid ones survive`() {
        val parsed = EvalSetParser.parse(
            """
            [
              {"query": "ok", "expectedFile": "a.pdf"},
              {"expectedFile": "missing-query.pdf"},
              {"query": "bad page", "expectedFile": "b.pdf", "expectedPage": 0},
              {"query": "  ", "expectedFile": "blank-query.pdf"},
              {"query": "also ok", "expectedFile": "c.pdf", "expectedPage": 3}
            ]
            """.trimIndent()
        )
        assertEquals(2, parsed.entries.size)
        assertEquals(3, parsed.invalid.size)
        // Reasons carry the entry index so the author can find the line.
        assertTrue(parsed.invalid.any { it.contains("1") })  // missing query
        assertTrue(parsed.invalid.any { it.contains("2") })  // expectedPage < 1 (pages are 1-indexed)
        assertTrue(parsed.invalid.any { it.contains("3") })  // blank query
    }

    // @spec SEARCH-EVAL-002
    @Test
    fun `malformed json and non-array roots are an error not a crash`() {
        assertTrue(EvalSetParser.parse("this is not json").entries.isEmpty())
        assertTrue(EvalSetParser.parse("{\"query\": \"not an array\"}").entries.isEmpty())
        assertTrue(EvalSetParser.parse("").entries.isEmpty())
        assertTrue(EvalSetParser.parse("this is not json").invalid.isNotEmpty())
    }

    // @spec SEARCH-EVAL-002
    @Test
    fun `unknown keys are ignored for forward compatibility`() {
        val parsed = EvalSetParser.parse(
            """[{"query": "q x", "expectedFile": "a.pdf", "note": "future field"}]"""
        )
        assertEquals(1, parsed.entries.size)
    }
}
