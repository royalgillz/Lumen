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
        sanitize(metadataTitle, filename)?.let { return it }
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
     * The sanity gate: trimmed, 3–120 chars, not the full filename echoed
     * back, not boilerplate, not filename-shaped, at least 40% letters.
     * A title equal to the filename STEM passes — it is a strictly nicer
     * display, and caption dedup prevents a duplicate line.
     */
    private fun sanitize(title: String?, filename: String): String? {
        val t = title?.trim() ?: return null
        if (t.length < 3 || t.length > 120) return null
        if (t.equals(filename, ignoreCase = true)) return null
        if (BOILERPLATE.any { it.matches(t) }) return null
        val lower = t.lowercase()
        if (DOCUMENT_EXTENSIONS.any { lower.endsWith(it) }) return null
        val letters = t.count { it.isLetter() }
        val nonWhitespace = t.count { !it.isWhitespace() }
        if (letters * 5 < nonWhitespace * 2) return null // < 40% letters
        return t
    }

    private const val FIRST_LINE_SCAN_LIMIT = 30

    private val BOILERPLATE = listOf(
        Regex("""untitled ?\d*""", RegexOption.IGNORE_CASE),
        Regex("""document ?\d*""", RegexOption.IGNORE_CASE),
        Regex("""presentation""", RegexOption.IGNORE_CASE),
        Regex("""slide ?1""", RegexOption.IGNORE_CASE),
        Regex("""microsoft word - .*""", RegexOption.IGNORE_CASE),
        Regex("""powerpoint .*""", RegexOption.IGNORE_CASE),
    )

    private val PAGE_MARKER = Regex("""page ?\d+""", RegexOption.IGNORE_CASE)

    private val DOCUMENT_EXTENSIONS = listOf(
        ".doc", ".docx", ".ppt", ".pptx", ".xls", ".xlsx",
        ".odt", ".tex", ".indd", ".qxd", ".pdf", ".rtf", ".txt",
    )
}
