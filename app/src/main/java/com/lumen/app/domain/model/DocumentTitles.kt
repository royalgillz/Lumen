package com.lumen.app.domain.model

/**
 * Display-title logic: what a document is called on every surface.
 * Resolution: custom title (user rename, URI-keyed store) > derived title
 * (sanity-gated auto-extraction, machine-generated filenames only) > filename.
 */
object DocumentTitles {

    /** The "attempted, nothing trustworthy found" marker for derivedTitle —
     *  distinguishes "never tried" (null) from "tried, keep the filename". */
    const val NONE = ""

    /**
     * True when the filename stem looks machine-generated (fewer than half of
     * its alphanumeric characters are letters): DOC-20260428-WA0006… yes;
     * Fall_25_I-20_Gill_Sehaj no. Only these documents get derived titles —
     * a meaningful filename is already the best identifier the user has.
     */
    // @spec LIB-TTL-006
    fun isMachineGeneratedName(filename: String): Boolean {
        val alnum = filename.substringBeforeLast('.').filter { it.isLetterOrDigit() }
        if (alnum.isEmpty()) return true
        val letters = alnum.count { it.isLetter() }
        return letters * 2 < alnum.length
    }

    /**
     * The derived title for a document, or [NONE]: sanity-gated metadata
     * title first, else the first plausible line of page-0 text (skipping
     * page numbers, dates, URLs), else [NONE]. Meaningfully-named documents
     * return [NONE] without deriving.
     */
    // @spec LIB-TTL-006, LIB-TTL-007
    fun deriveTitle(metadataTitle: String?, pageZeroText: String?, filename: String): String {
        if (!isMachineGeneratedName(filename)) return NONE
        sanitize(metadataTitle, filename, fromMetadata = true)?.let { return it }
        pageZeroText?.lineSequence()?.take(FIRST_LINE_SCAN_LIMIT)?.forEach { line ->
            val candidate = line.trim()
            if (candidate.contains("://") || candidate.startsWith("www.", ignoreCase = true)) return@forEach
            if (PAGE_MARKER.matches(candidate)) return@forEach
            sanitize(candidate, filename)?.let { return it }
        }
        return NONE
    }

    /** customTitle > non-blank derivedTitle > filename. */
    // @spec LIB-TTL-001
    fun displayTitle(customTitle: String?, derivedTitle: String?, filename: String): String =
        customTitle?.takeIf { it.isNotBlank() }
            ?: derivedTitle?.takeIf { it.isNotBlank() }
            ?: filename

    /**
     * The stored author for a document, or null: whitespace-collapsed and
     * trimmed, rejected only when blank or absurdly long. No junk reject-list
     * beyond that — the author is labeled as metadata where shown, so an
     * honest-but-ugly value ("Microsoft Office User") is information, not a
     * heuristic masquerading as a title.
     */
    // @spec LIB-TTL-012
    fun sanitizeAuthor(author: String?): String? =
        author?.replace(CONTROL_OR_WHITESPACE_RUN, " ")?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_TITLE_LENGTH }

    /**
     * The sanity gate: whitespace-collapsed, 3–120 chars, not the full
     * filename echoed back, not generator boilerplate, not filename- or
     * path-shaped, at least 40% letters. A title equal to the filename STEM
     * passes — it is a strictly nicer display, and caption dedup prevents a
     * duplicate line.
     *
     * Metadata-sourced candidates get two narrow allowances page-0 lines do
     * not: an author actually typed the field, so a short numeric title
     * ("1984", "1Q84" — ≤6 chars, not date-shaped) and a 2-character
     * all-letter title (CJK book titles: 论语, 史记) are real; the same shapes
     * on page 0 are page furniture.
     */
    private fun sanitize(title: String?, filename: String, fromMetadata: Boolean = false): String? {
        val t = title?.replace(CONTROL_OR_WHITESPACE_RUN, " ")?.trim() ?: return null
        val minLength = if (fromMetadata && t.isNotEmpty() && t.all { it.isLetter() }) 2 else 3
        if (t.length < minLength || t.length > MAX_TITLE_LENGTH) return null
        if (t.equals(filename, ignoreCase = true)) return null
        if (BOILERPLATE.any { it.matches(t) }) return null
        if (t.contains('\\') || t.startsWith("/") || DRIVE_PATH.matches(t)) return null
        val lower = t.lowercase()
        if (DOCUMENT_EXTENSIONS.any { lower.endsWith(it) }) return null
        val letters = t.count { it.isLetter() }
        val nonWhitespace = t.count { !it.isWhitespace() }
        if (letters * 5 < nonWhitespace * 2) { // < 40% letters
            val numericMetadataTitle = fromMetadata && t.length <= 6 && !DATE_SHAPED.matches(t)
            if (!numericMetadataTitle) return null
        }
        return t
    }

    private const val FIRST_LINE_SCAN_LIMIT = 30
    private const val MAX_TITLE_LENGTH = 120

    // Metadata fields can carry embedded newlines and stray control bytes.
    private val CONTROL_OR_WHITESPACE_RUN = Regex("""[\p{Cntrl}\s]+""")

    // "C:\…" is caught by the backslash check; this catches "C:/…".
    private val DRIVE_PATH = Regex("""[a-zA-Z]:[/\\].*""")

    // Authoring-tool defaults and scanner output, anchored to the actual
    // generator shapes — real titles that merely start with or contain a
    // pattern ("Microsoft Excel 2019 Bible", "Untitled: The Real Wallis
    // Simpson", "Document Retention Policy") pass. The one deliberate
    // contains-match is CamScanner, whose watermark embeds arbitrary text.
    private val BOILERPLATE = listOf(
        """untitled( document| presentation| spreadsheet)?( ?-? ?\d+)?""",
        """(blank |word |new |pdf )?document ?\d*""",
        """doc ?\d+""",
        """presentation ?\d*""",
        """slide ?\d+""",
        """(book|sheet|workbook|layout) ?\d+""",
        """microsoft (word|excel|powerpoint|office)( ?-.*)?""",
        """powerpoint([ -]?(presentation|präsentation))?( ?\d+)?""",
        """adobe photoshop pdf""",
        """full page photo""",
        """print(out)?""",
        """scan(ned)?( ?\d+| document| image| doc)?""",
        """.*camscanner.*""",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    private val PAGE_MARKER = Regex("""page ?\d+""", RegexOption.IGNORE_CASE)

    // Compact dates masquerading as titles: 6–8 digit runs (20260428) and
    // separator dates (28-04-26, 2026/04/28). A bare 4-digit number is NOT
    // date-shaped — "1984" is a book.
    private val DATE_SHAPED = Regex("""\d{6,8}|\d{1,4}[-/.]\d{1,2}([-/.]\d{1,4})?""")

    private val DOCUMENT_EXTENSIONS = listOf(
        ".doc", ".docx", ".ppt", ".pptx", ".xls", ".xlsx",
        ".odt", ".odp", ".ods", ".tex", ".indd", ".qxd", ".pdf", ".rtf", ".txt",
        ".wpd", ".pub", ".xps", ".htm", ".html", ".dvi", ".psd",
        ".jpg", ".jpeg", ".png", ".tif", ".tiff", ".pages", ".key", ".numbers",
    )
}
