package com.lumen.app.data.fs

/**
 * Which access escape hatch (if any) Lumen offers for an externally opened
 * document whose grant is transient. [LUMEN_PDFS_FLOW] is never exclusive —
 * the UI presents it alongside the [KEEP_FILE] re-pick.
 */
enum class ExternalAccessOffer {
    /** Nothing to offer: access already persists, a library folder covers the
     *  document, or the user dismissed the offer. */
    NONE,

    /** Tree picker pre-aimed at the containing folder — device-storage
     *  documents whose folder the picker can actually grant. */
    ADD_FOLDER,

    /** Single-file ACTION_OPEN_DOCUMENT re-pick — the one persistable grant
     *  available when no folder grant is possible. */
    KEEP_FILE,

    /** Guided create-Download/Lumen-pdfs → user moves the file → add-folder
     *  flow, for Download-root documents on API >= 30 where the root itself is
     *  ungrantable. Offered alongside [KEEP_FILE]. */
    LUMEN_PDFS_FLOW,
}

/**
 * Pure decision matrix for the external-access offers. Callers supply the
 * three framework-bound facts as booleans (persisted grant held, library
 * coverage, dismissal); everything else derives from the URI string and the
 * API level, so every cell of the matrix is JVM-testable.
 */
object ExternalAccessOffers {

    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /** First API level where the tree picker refuses volume roots, the
     *  Download directory, and Android/data|obb. */
    const val RESTRICTED_PICKER_API = 30

    // @spec LIB-EXT-007, LIB-EXT-008, LIB-EXT-009, LIB-EXT-010
    fun decide(
        docUri: String,
        apiLevel: Int,
        hasPersistedGrant: Boolean,
        coveredByLibraryTree: Boolean,
        offerDismissed: Boolean,
    ): ExternalAccessOffer {
        if (hasPersistedGrant || coveredByLibraryTree || offerDismissed) {
            return ExternalAccessOffer.NONE
        }
        // Non-device-storage providers (Downloads provider, Drive, mailers…)
        // expose no folder tree Lumen could add; the single-file re-pick is
        // the one persistable path.
        if (DocumentLocations.authorityOf(docUri) != EXTERNAL_STORAGE_AUTHORITY) {
            return ExternalAccessOffer.KEEP_FILE
        }
        val documentId = DocumentLocations.documentIdOf(docUri)
        val folderId = documentId?.let { DocumentLocations.containingFolderIdOf(it) }
            ?: return ExternalAccessOffer.KEEP_FILE // malformed / underivable
        if (DocumentLocations.containingFolderGrantable(docUri, apiLevel)) {
            return ExternalAccessOffer.ADD_FOLDER
        }
        // Grant-restricted folders (API >= 30 from here on): the Download root
        // gets the guided move flow; volume roots and Android/data|obb only
        // the file re-pick.
        if (DocumentLocations.isDownloadsRoot(folderId)) {
            return ExternalAccessOffer.LUMEN_PDFS_FLOW
        }
        return ExternalAccessOffer.KEEP_FILE
    }
}

/**
 * `EXTRA_INITIAL_URI` values for the offers' pickers, as URI strings — what
 * `DocumentsContract.buildDocumentUri` would produce, kept framework-free so
 * construction is JVM-testable (mirrors `quickPickInitialUriString` in
 * ui/common).
 */
// @spec LIB-EXT-013
object ExternalAccessPickerTargets {

    /** Aim the ADD_FOLDER tree picker (or a KEEP_FILE re-pick) at the
     *  document's own containing folder. Null when the URI is not a
     *  device-storage document or no folder is derivable. */
    fun containingFolderInitialUri(docUri: String): String? {
        if (DocumentLocations.authorityOf(docUri) != ExternalAccessOffers.EXTERNAL_STORAGE_AUTHORITY) {
            return null
        }
        val id = DocumentLocations.documentIdOf(docUri) ?: return null
        val folder = DocumentLocations.containingFolderIdOf(id) ?: return null
        return documentUriString(folder)
    }

    /** Aim a picker at the Download folder (on-disk name has no 's'). */
    fun downloadsInitialUri(): String = documentUriString("primary:Download")

    /** Aim the LUMEN_PDFS_FLOW add-folder picker at Download/Lumen-pdfs. */
    fun lumenPdfsInitialUri(): String = documentUriString(LumenPdfsFolder.DOCUMENT_ID)

    private fun documentUriString(documentId: String): String =
        "content://${ExternalAccessOffers.EXTERNAL_STORAGE_AUTHORITY}/document/" +
            encodeExternalDocumentId(documentId)
}

// android.net.Uri's path-segment encoding: these stay literal, everything else
// is percent-encoded per UTF-8 byte (':' → %3A — the character every SAF
// document id contains). Duplicates ui/common's encoder because data/fs must
// not depend on a UI package.
private const val URI_SEGMENT_LITERALS =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-!.~'()*"

internal fun encodeExternalDocumentId(documentId: String): String = buildString {
    for (b in documentId.toByteArray(Charsets.UTF_8)) {
        val i = b.toInt() and 0xFF
        if (i < 0x80 && i.toChar() in URI_SEGMENT_LITERALS) {
            append(i.toChar())
        } else {
            append('%').append("%02X".format(i))
        }
    }
}
