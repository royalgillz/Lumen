package com.lumen.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("a")) // below even the 2-char metadata floor
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("x".repeat(121))) // > 120
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("2026-05-05")) // < 40% letters
    }

    // Real-world generator boilerplate the original list let through.
    // @spec LIB-TTL-007
    @Test
    fun metadata_rejectsRealWorldGeneratorJunk() {
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("Untitled document"))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("untitled-1"))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("Blank document"))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("New Document 2"))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("PDF Document"))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("Doc1"))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("Presentation1"))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("Book1"))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("Sheet1"))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("Workbook 2"))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("Layout 1"))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("Microsoft PowerPoint - Presentation1"))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("Microsoft Excel - Book1"))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("PowerPoint-Präsentation"))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("Adobe Photoshop PDF"))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("Full page photo"))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("Print"))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("Printout"))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("Scan"))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("Scanned Document"))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("Scan 05072026"))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("Scanned by CamScanner"))
    }

    // Anchored patterns must not swallow real titles that merely start with a
    // generator word.
    // @spec LIB-TTL-007
    @Test
    fun metadata_realTitlesStartingWithGeneratorWords_pass() {
        assertEquals(
            "Microsoft Excel 2019 Bible",
            deriveFromMetadata("Microsoft Excel 2019 Bible"),
        )
        assertEquals(
            "Untitled: The Real Wallis Simpson",
            deriveFromMetadata("Untitled: The Real Wallis Simpson"),
        )
    }

    // @spec LIB-TTL-007
    @Test
    fun metadata_rejectsPathAndFilenameShapedTitles() {
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("""C:\Users\jane\Desktop\final.doc"""))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("C:/scans/output"))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("/home/jane/thesis"))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("resume.jpg"))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("newsletter_v3.pub"))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("slides-final.odp"))
        assertEquals(DocumentTitles.NONE, deriveFromMetadata("index.html"))
    }

    // Boilerplate rejection is full-match: real titles that merely contain a
    // junk word must pass.
    // @spec LIB-TTL-007
    @Test
    fun metadata_junkPatternsInsideRealTitlesStillPass() {
        assertEquals("Scandinavian Design History", deriveFromMetadata("Scandinavian Design History"))
        assertEquals("Document Retention Policy", deriveFromMetadata("Document Retention Policy"))
        assertEquals("Printing Money: A History", deriveFromMetadata("Printing Money: A History"))
    }

    // Metadata strings can carry embedded newlines and control bytes; they are
    // collapsed to single spaces, never shown raw.
    // @spec LIB-TTL-007
    @Test
    fun metadata_collapsesWhitespaceAndControlCharacters() {
        assertEquals("Visa Approval Notice", deriveFromMetadata("Visa\r\nApproval\tNotice"))
        assertEquals("Visa Approval", deriveFromMetadata("Visa\u0000 Approval"))
    }

    // ── Author sanitization ───────────────────────────────────────────────────

    // @spec LIB-TTL-012
    @Test
    fun author_trimmedAndCollapsed() {
        assertEquals("Jane Q. Public", DocumentTitles.sanitizeAuthor("  Jane  Q.\tPublic "))
        assertEquals("Jane Doe", DocumentTitles.sanitizeAuthor("Jane\r\nDoe"))
    }

    // @spec LIB-TTL-012
    @Test
    fun author_blankOrAbsurdBecomesNull() {
        assertNull(DocumentTitles.sanitizeAuthor(null))
        assertNull(DocumentTitles.sanitizeAuthor("   "))
        assertNull(DocumentTitles.sanitizeAuthor("\u0000\u0007"))
        assertNull(DocumentTitles.sanitizeAuthor("x".repeat(121)))
    }

    // Deliberately no junk reject-list for authors: the value is labeled as
    // metadata where shown, so honest-but-ugly stays.
    // @spec LIB-TTL-012
    @Test
    fun author_uglyButHonestValuesKept() {
        assertEquals("Microsoft Office User", DocumentTitles.sanitizeAuthor("Microsoft Office User"))
        assertEquals("admin", DocumentTitles.sanitizeAuthor("admin"))
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

    // Metadata-only allowances: an author typed these; page-0 stays strict.
    // @spec LIB-TTL-007
    @Test
    fun metadata_shortNumericAndTwoCharTitles_acceptedFromMetadataOnly() {
        assertEquals("1984", DocumentTitles.deriveTitle("1984", null, generated))
        assertEquals("1Q84", DocumentTitles.deriveTitle("1Q84", null, generated))
        assertEquals("论语", DocumentTitles.deriveTitle("论语", null, generated))
        // Date-shaped numerics stay rejected even from metadata.
        assertEquals(DocumentTitles.NONE, DocumentTitles.deriveTitle("20260428", null, generated))
        assertEquals(DocumentTitles.NONE, DocumentTitles.deriveTitle("28-04-26", null, generated))
        // The same shapes on page 0 are page furniture, not titles.
        assertEquals(DocumentTitles.NONE, DocumentTitles.deriveTitle(null, "1984", generated))
        assertEquals(DocumentTitles.NONE, DocumentTitles.deriveTitle(null, "论语", generated))
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
