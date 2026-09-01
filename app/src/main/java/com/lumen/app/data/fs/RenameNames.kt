package com.lumen.app.data.fs

/**
 * The user edits a name stem; the `.pdf` extension is preserved automatically
 * and never doubled. Returns null for an empty stem or a path separator —
 * the only inputs rejected app-side; anything else is the provider's call.
 */
// @spec LIB-REN-002, LIB-REN-010
fun renameTargetFilename(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.contains('/') || trimmed.contains('\\')) return null
    val hasExtension = trimmed.endsWith(".pdf", ignoreCase = true)
    val stem = if (hasExtension) trimmed.dropLast(4) else trimmed
    if (stem.isBlank()) return null
    return if (hasExtension) trimmed else "$trimmed.pdf"
}

/**
 * True when the typed name resolves to the document's current filename —
 * stems compared case-sensitively, the extension case-insensitively. A
 * same-name save skips the provider call but still applies the one-truth
 * clearing.
 */
// @spec LIB-REN-010
fun isSameFilename(input: String, currentFilename: String): Boolean {
    val target = renameTargetFilename(input) ?: return false
    return stemOf(target) == stemOf(currentFilename)
}

/**
 * True when the typed name changes the stem only by letter case
 * ("report" → "Report"). Case-insensitive storage (FUSE/FAT) reports such a
 * target as already existing, so it renames through a temporary name instead
 * of a direct provider call.
 */
// @spec LIB-REN-013
fun isCaseOnlyRename(input: String, currentFilename: String): Boolean {
    val target = renameTargetFilename(input) ?: return false
    if (isSameFilename(input, currentFilename)) return false
    return stemOf(target).equals(stemOf(currentFilename), ignoreCase = true)
}

/** Collision-free intermediate name for the case-only two-step rename. */
// @spec LIB-REN-013
fun caseRenameTempFilename(target: String, nonce: Long): String =
    "${stemOf(target)}.rename-$nonce.pdf"

/**
 * The intended final filename embedded in a reserved case-rename temp name
 * ("Report.rename-1723456789.pdf" → "Report.pdf"), or null for any other
 * filename. A scan seeing such a file found a two-step rename that died
 * between steps; the embedded stem is the name the user chose.
 */
// @spec LIB-REN-015
fun caseRenameTempTarget(filename: String): String? {
    val match = CASE_RENAME_TEMP.matchEntire(filename) ?: return null
    return "${match.groupValues[1]}.pdf"
}

private val CASE_RENAME_TEMP = Regex("""(.+)\.rename-\d+\.pdf""", RegexOption.IGNORE_CASE)

/**
 * True when the provider renamed in place: stable-ID providers (Nextcloud,
 * cloud, some USB storage) keep a document's URI across a rename and return
 * the original one — the identity is unchanged, so no re-key or orphan purge
 * may run (purging the "new" URI would delete the document's own rows).
 */
// @spec LIB-REN-011
fun isInPlaceRename(oldUri: String, returnedUri: String): Boolean =
    oldUri == returnedUri

private fun stemOf(filename: String): String =
    if (filename.endsWith(".pdf", ignoreCase = true)) filename.dropLast(4) else filename
