package com.lumen.app.data.text

import org.junit.Assert.assertEquals
import org.junit.Test

class TextNormalizerTest {

    // @spec SEARCH-NORM-001
    @Test
    fun setP_charactersDeleted() {
        assertEquals("F1", TextNormalizer.normalize("F-1"))
        assertEquals("F1", TextNormalizer.normalize("F.1"))
        assertEquals("I20", TextNormalizer.normalize("I-20"))
        assertEquals("80211", TextNormalizer.normalize("802.11"))
        assertEquals("1000", TextNormalizer.normalize("1,000"))
        assertEquals("1030", TextNormalizer.normalize("10:30"))
        assertEquals("dont", TextNormalizer.normalize("don't"))
        assertEquals("dont", TextNormalizer.normalize("don’t"))
    }

    // @spec SEARCH-NORM-001
    @Test
    fun unicodeDashes_deleted() {
        assertEquals("F1", TextNormalizer.normalize("F‐1")) // U+2010 hyphen
        assertEquals("F1", TextNormalizer.normalize("F–1")) // en dash
        assertEquals("F1", TextNormalizer.normalize("F—1")) // em dash
    }

    // @spec SEARCH-NORM-001
    @Test
    fun lineWrapHyphenation_collapsedBeforePunctuationPass() {
        assertEquals("COVID19", TextNormalizer.normalize("COVID-\n19"))
        assertEquals("COVID19", TextNormalizer.normalize("COVID-\r\n19"))
        // A hyphen NOT followed by a newline is plain set-P deletion.
        assertEquals("COVID19", TextNormalizer.normalize("COVID-19"))
    }

    // @spec SEARCH-NORM-001
    @Test
    fun slashAndUnderscore_preserved() {
        // '/' stays: deleting it would merge dates ("05/07/2026") and kill
        // component queries like "2026". '_' stays for the same token-merging reason.
        assertEquals("05/07/2026", TextNormalizer.normalize("05/07/2026"))
        assertEquals("file_name", TextNormalizer.normalize("file_name"))
    }

    // @spec SEARCH-NORM-001
    @Test
    fun caseAndWhitespace_preserved() {
        assertEquals("Hello World", TextNormalizer.normalize("Hello World"))
        assertEquals("a  b\nc", TextNormalizer.normalize("a  b\nc"))
        // A bare newline (no preceding hyphen) is untouched.
        assertEquals("foo\nbar", TextNormalizer.normalize("foo\nbar"))
    }

    // @spec SEARCH-NORM-001
    @Test
    fun punctuationBetweenSpaces_deletedWithoutMergingWords() {
        // The '-' between spaces is deleted but the spaces still separate the words.
        assertEquals("foo  bar", TextNormalizer.normalize("foo - bar"))
    }

    // @spec SEARCH-NORM-001
    @Test
    fun emptyAndPunctuationOnly() {
        assertEquals("", TextNormalizer.normalize(""))
        assertEquals("", TextNormalizer.normalize("-.-,:"))
    }
}
