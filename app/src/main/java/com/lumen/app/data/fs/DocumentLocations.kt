package com.lumen.app.data.fs

import java.net.URLDecoder

/**
 * Location derivation for indexed documents. Lumen stores SAF tree-form
 * document URIs (`content://<authority>/tree/<treeId>/document/<docId>`); the
 * percent-decoded document id carries the volume-qualified path
 * (`primary:Docs/Reports/report.pdf`). Everything here is pure string work so
 * it stays JVM-testable; building the folder's Uri for the open-folder intent
 * happens at the UI layer via DocumentsContract.
 */
object DocumentLocations {

    /** Percent-decoded document id of a tree-form document URI, or null. */
    fun documentIdOf(docUri: String): String? {
        val encoded = docUri.substringAfterLast("/document/", "")
        if (encoded.isEmpty()) return null
        return decode(encoded)
    }

    /** Percent-decoded tree document id of a tree-form URI, or null. */
    fun treeDocumentIdOf(docUri: String): String? {
        val tail = docUri.substringAfter("/tree/", "")
        if (tail.isEmpty()) return null
        return decode(tail.substringBefore('/'))
    }

    /**
     * Decoded id of the document's containing folder: the document id minus
     * its last path segment, or the tree's own id when the file sits directly
     * under the picked volume root. Null when no folder can be derived.
     */
    // @spec LIB-LOC-002
    fun parentDocumentId(docUri: String): String? {
        val id = documentIdOf(docUri) ?: return null
        val path = id.substringAfter(':', id)
        if (!path.contains('/')) return treeDocumentIdOf(docUri)
        return id.substringBeforeLast('/')
    }

    /**
     * Human-readable folder path for the Location row ("Docs/Reports"), the
     * volume label when the file sits at the volume root, or null when the
     * URI carries no derivable location.
     */
    // @spec LIB-LOC-001
    fun folderDisplayPath(docUri: String): String? {
        val id = documentIdOf(docUri) ?: return null
        val volume = id.substringBefore(':', "")
        val path = id.substringAfter(':', id)
        val folder = path.substringBeforeLast('/', "")
        if (folder.isNotBlank()) return folder
        return volumeLabel(volume)
    }

    private fun volumeLabel(volume: String): String? = when {
        volume.equals("primary", ignoreCase = true) -> "Internal storage"
        volume.isNotBlank() -> volume // SD/USB volumes show their id
        else -> null
    }

    // ── External-access grantability ──────────────────────────────────────────
    // Pure predicates over decoded document ids, feeding the external-access
    // offer matrix (ExternalAccessOffers). They mirror the tree-picker
    // restrictions Android 11 introduced (DocumentsUI refuses to grant volume
    // roots, the Download directory, and Android/data|obb on API >= 30).

    /** Provider authority of a content URI string, or null for anything else. */
    fun authorityOf(uri: String): String? {
        if (!uri.startsWith("content://")) return null
        return uri.removePrefix("content://").substringBefore('/').ifEmpty { null }
    }

    /**
     * Decoded id of the folder containing [documentId]'s file: the id minus
     * its last path segment, or the volume-root id ("primary:") for a file
     * sitting directly on the volume. Null when the id is not volume-qualified
     * or names the volume root itself (no containing folder to speak of).
     */
    // @spec LIB-EXT-008
    fun containingFolderIdOf(documentId: String): String? {
        val sep = documentId.indexOf(':')
        if (sep < 0) return null
        val path = documentId.substring(sep + 1)
        if (path.isEmpty()) return null
        if (!path.contains('/')) return documentId.substring(0, sep + 1)
        return documentId.substringBeforeLast('/')
    }

    /** Whether [folderId] is a volume's Download directory (the on-disk name
     *  is literally "Download"). Volume-agnostic and case-insensitive: the
     *  tree-picker restriction names "the Download directory" without
     *  qualifying the volume, and FUSE storage is case-insensitive. */
    // @spec LIB-EXT-010
    fun isDownloadsRoot(folderId: String): Boolean =
        pathOf(folderId).equals("Download", ignoreCase = true)

    /** Whether [folderId] names a volume root ("primary:", "1A2B-3C4D:"). */
    // @spec LIB-EXT-008
    fun isVolumeRoot(folderId: String): Boolean =
        folderId.contains(':') && folderId.substringAfter(':').isEmpty()

    /** Whether [documentId] sits at or under Android/data or Android/obb —
     *  app-private directories the tree picker refuses on API >= 30. */
    // @spec LIB-EXT-008
    fun isRestrictedPath(documentId: String): Boolean {
        val path = pathOf(documentId) ?: return false
        return RESTRICTED_ROOTS.any { root ->
            path.equals(root, ignoreCase = true) ||
                path.startsWith("$root/", ignoreCase = true)
        }
    }

    /**
     * Whether ACTION_OPEN_DOCUMENT_TREE can grant the folder containing
     * [docUri]'s document. Before API 30 the picker grants anything (volume
     * and Download roots included); from API 30 it refuses volume roots, the
     * Download directory, and Android/data|obb. False when no containing
     * folder is derivable at all.
     */
    // @spec LIB-EXT-008
    fun containingFolderGrantable(docUri: String, apiLevel: Int): Boolean {
        val id = documentIdOf(docUri) ?: return false
        val folder = containingFolderIdOf(id) ?: return false
        if (apiLevel < 30) return true
        return !isVolumeRoot(folder) && !isDownloadsRoot(folder) && !isRestrictedPath(folder)
    }

    private val RESTRICTED_ROOTS = listOf("Android/data", "Android/obb")

    /** Path part of a volume-qualified document id, or null without a ':'. */
    private fun pathOf(documentId: String): String? {
        val sep = documentId.indexOf(':')
        if (sep < 0) return null
        return documentId.substring(sep + 1)
    }

    // URLDecoder is the one JVM-available percent-decoder, but it maps '+' to
    // a space (form encoding) — re-escape it first so filenames keep theirs.
    private fun decode(encoded: String): String? =
        runCatching { URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8") }.getOrNull()
}
