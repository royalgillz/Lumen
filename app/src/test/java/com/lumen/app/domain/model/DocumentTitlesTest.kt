package com.lumen.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentTitlesTest {

    // ── Machine-generated filename gate ──────────────────────────────────────

    // @spec LIB-TTL-006
    @Test
    fun machineGeneratedNames_detected() {
        assertTrue(DocumentTitles.isMachineGeneratedName("DOC-20260428-WA0006_260507_132012.pdf"))
        assertTrue(DocumentTitles.isMachineGeneratedName("IMG_20260101_123456.pdf"))
        assertTrue(DocumentTitles.isMachineGeneratedName("2026-05-05.pdf"))
    }

    // @spec LIB-TTL-006
    @Test
    fun meaningfulNames_leftAlone() {
        assertFalse(DocumentTitles.isMachineGeneratedName("Fall_25_I-20_Gill_Sehaj.pdf"))
        assertFalse(DocumentTitles.isMachineGeneratedName("aws-ai-practitioner-cheat-sheet.pdf"))
        assertFalse(DocumentTitles.isMachineGeneratedName("nsdi19-jog.pdf"))
        assertFalse(DocumentTitles.isMachineGeneratedName("draft.pdf"))
    }

    // @spec LIB-TTL-006
    @Test
    fun meaningfulFilename_skipsDerivationEvenWithGoodMetadata() {
        val derived = DocumentTitles.deriveTitle(
            metadataTitle = "A Perfectly Good Title",
            pageZeroText = "A Perfectly Good Heading\nbody text",
            filename = "aws-ai-practitioner-cheat-sheet.pdf",
        )
        assertEquals(DocumentTitles.NONE, derived)
    }

    // ── Metadata sanity gate (via deriveTitle on a generated filename) ───────

    private val generated = "DOC-20260428-WA0006_260507_132012.pdf"

    private fun deriveFromMetadata(title: String?): String =
        DocumentTitles.deriveTitle(metadataTitle = title, pageZeroText = null, filename = generated)

    // @spec LIB-TTL-007
    @Test
    fun metadata_acceptsPlausibleTitles() {
        assertEquals("Visa Approval Notice", deriveFromMetadata("Visa Approval Notice"))
        assertEquals("802.11 Specification", deriveFromMetadata("802.11 Specification"))
        assertEquals("Visa Approval", deriveFromMetadata("  Visa Approval  "))
    }

    // @spec LIB-TTL-007
    @Test
    fun metadata_stemEqualAccepted_fullFilenameRejected() {
        // A title equal to the filename STEM is a strictly nicer display (no .pdf).
        val stemNamed = DocumentTitles.deriveTitle(
            metadataTitle = "2026 Annual Report",
            pageZeroText = null,
            filename = "20260428_2026_Annual_Report_001122334455.pdf",
        )
        assertEquals("2026 Annual Report", stemNamed)
        // The FULL filename echoed into metadata is worthless.
        assertEquals(DocumentTitles.NONE, deriveFromMetadata(generated))
    }

    // @spec LIB-TTL-007
    @Test
    fun metadata_rejectsBoilerplateAndJunk() {
        assertEquals(DocumentTitles.NONE, deriveFromMetadata(null))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("   "))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("untitled"))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("Untitled 3"))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("Document1"))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("Presentation"))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("Slide 1"))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("Microsoft Word - final_v2.docx"))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("PowerPoint Presentation"))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("final_v2.docx"))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("ab")) // < 3 chars
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("x".repeat(121))) // > 120
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("2026-05-05")) // < 40% letters
    }

    // ── First-line fallback ───────────────────────────────────────────────────

    // @spec LIB-TTL-007
    @Test
    fun firstLine_picksFirstPlausibleLine() {
        val text = "Page 1\n05/07/2026\nhttps://example.com/form\n" +
            "Department of Homeland Security\nI-20 Certificate of Eligibility"
        assertEquals(
            "Department of Homeland Security",
            DocumentTitles.deriveTitle(metadataTitle = null, pageZeroText = text, filename = generated),
        )
    }

    // @spec LIB-TTL-007
    @Test
    fun firstLine_nothingPlausible_returnsSentinel() {
        assertEquals(
            DocumentTitles.NONE,
            DocumentTitles.deriveTitle(metadataTitle = null, pageZeroText = "1\n2\n3", filename = generated),
        )
        assertEquals(
            DocumentTitles.NONE,
            DocumentTitles.deriveTitle(metadataTitle = null, pageZeroText = null, filename = generated),
        )
    }

    // @spec LIB-TTL-007
    @Test
    fun metadata_winsOverFirstLine() {
        val derived = DocumentTitles.deriveTitle(
            metadataTitle = "Visa Approval Notice",
            pageZeroText = "Some Heading Line",
            filename = generated,
        )
        assertEquals("Visa Approval Notice", derived)
    }

    // ── Resolution precedence ─────────────────────────────────────────────────

    // @spec LIB-TTL-001
    @Test
    fun displayTitle_precedence() {
        assertEquals("My Doc", DocumentTitles.displayTitle("My Doc", "Derived", "f.pdf"))
        assertEquals("Derived", DocumentTitles.displayTitle(null, "Derived", "f.pdf"))
        assertEquals("f.pdf", DocumentTitles.displayTitle(null, DocumentTitles.NONE, "f.pdf"))
        assertEquals("f.pdf", DocumentTitles.displayTitle(null, null, "f.pdf"))
    }
}
