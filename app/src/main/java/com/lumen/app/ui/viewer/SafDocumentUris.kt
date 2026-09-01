package com.lumen.app.ui.viewer

/**
 * Pure string-space identity helpers for SAF content URIs, mirroring
 * DocumentsContract's path grammar without the framework dependency (which
 * keeps the matching JVM-testable). A provider document is identified by
 * (authority, document id); the same file appears both as
 * `content://auth/tree/T/document/D` (library rows, from tree traversal) and
 * `content://auth/document/D` (VIEW intents), so string equality can never
 * connect the two forms.
 */
// @spec VIEW-EXT-002
object SafDocumentUris {

    /** Authority of a content:// URI, or null for anything else. */
    fun authorityOf(uri: String): String? {
        if (!uri.startsWith("content://")) return null
        return uri.removePrefix("content://").substringBefore('/').takeIf { it.isNotEmpty() }
    }

    /**
     * The percent-decoded SAF document id of a document-form
     * (`content://auth/document/D`) or tree-form
     * (`content://auth/tree/T/document/D`) document URI; null when the URI has
     * neither shape. Decoded before comparison because providers are free to
     * vary percent-encoding between the two forms of the same id.
     */
    fun documentIdOf(uri: String): String? {
        if (!uri.startsWith("content://")) return null
        val path = uri.removePrefix("content://").substringAfter('/', missingDelimiterValue = "")
        if (path.isEmpty()) return null
        val segments = path.split('/')
        val encodedId = when {
            segments.size == 2 && segments[0] == "document" -> segments[1]
            segments.size == 4 && segments[0] == "tree" && segments[2] == "document" -> segments[3]
            else -> null
        }
        return encodedId?.takeIf { it.isNotEmpty() }?.let(::percentDecode)
    }

    /** True when both URIs name the same provider document. */
    fun sameDocument(a: String, b: String): Boolean {
        val authority = authorityOf(a) ?: return false
        if (authority != authorityOf(b)) return false
        val docId = documentIdOf(a) ?: return false
        return docId == documentIdOf(b)
    }

    // Uri.decode without the framework: %XX escapes only ('+' stays literal,
    // unlike URLDecoder); malformed escapes pass through as-is, matching
    // Uri.decode's behavior.
    private fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val out = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hi = Character.digit(s[i + 1], 16)
                val lo = Character.digit(s[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    out.write((hi shl 4) or lo)
                    i += 3
                    continue
                }
            }
            // Literal (unescaped) char — take a surrogate pair whole so its
            // UTF-8 bytes come out intact.
            val end = if (Character.isHighSurrogate(c) && i + 1 < s.length) i + 2 else i + 1
            out.write(s.substring(i, end).toByteArray(Charsets.UTF_8))
            i = end
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
