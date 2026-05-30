package com.lumen.app.ui.common

import android.net.Uri
import android.provider.DocumentsContract

/**
 * A human-friendly display name for a SAF tree [uri].
 *
 * SAF tree URIs carry a document id like `primary:Android/media/com.whatsapp/Docs`.
 * Showing that raw tail looks like a bug; this returns just the final folder name
 * (`Docs`), falling back to the path or the last URI segment when no better label
 * is available.
 */
fun folderDisplayName(uri: Uri): String {
    val raw = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment
        ?: uri.toString()
    val afterColon = raw.substringAfter(':', raw)
    val last = afterColon.trimEnd('/').substringAfterLast('/')
    return last.ifBlank { afterColon.ifBlank { raw } }
}
