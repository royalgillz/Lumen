package com.lumen.app.ui.common

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Quick-pick targets for the SAF folder picker: the well-known folders on the
 * primary external-storage volume where users' PDFs actually live. A chip only
 * LANDS the picker there via EXTRA_INITIAL_URI — SAF still requires the user
 * to confirm the grant in the picker, and providers that ignore the extra
 * simply open at their default location.
 */
// @spec LIB-QPK-001, LIB-QPK-002
enum class QuickPickFolder(val label: String, val documentId: String) {
    // Android's on-disk folder is literally named "Download" (no s); the chip
    // label uses the plural users know from the Files app.
    DOWNLOADS("Downloads", "primary:Download"),
    DOCUMENTS("Documents", "primary:Documents"),
}

private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

/** The document URI string for [documentId] on the external-storage provider —
 *  what `DocumentsContract.buildDocumentUri` produces, kept framework-free so
 *  the construction is JVM-testable. */
// @spec LIB-QPK-002
fun quickPickInitialUriString(documentId: String): String =
    "content://$EXTERNAL_STORAGE_AUTHORITY/document/${encodeDocumentId(documentId)}"

// android.net.Uri's path-segment encoding: these stay literal, everything else
// is percent-encoded per UTF-8 byte (':' → %3A — the character every SAF
// document id contains).
private const val URI_SEGMENT_LITERALS =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-!.~'()*"

internal fun encodeDocumentId(documentId: String): String = buildString {
    for (b in documentId.toByteArray(Charsets.UTF_8)) {
        val i = b.toInt() and 0xFF
        if (i < 0x80 && i.toChar() in URI_SEGMENT_LITERALS) {
            append(i.toChar())
        } else {
            append('%').append("%02X".format(i))
        }
    }
}

/**
 * The quick-pick chip row shown wherever the app invites a first folder: the
 * empty-library state and the onboarding folder step. [onPick] receives the
 * initial URI to launch the standard OpenDocumentTree flow with, or null for
 * the generic choose-anywhere affordance.
 */
// @spec LIB-QPK-001, LIB-QPK-002
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FolderQuickPickRow(
    onPick: (Uri?) -> Unit,
    modifier: Modifier = Modifier,
    showChooseFolder: Boolean = true,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QuickPickFolder.entries.forEach { target ->
            QuickPickChip(label = target.label, showIcon = true) {
                onPick(Uri.parse(quickPickInitialUriString(target.documentId)))
            }
        }
        if (showChooseFolder) {
            QuickPickChip(label = "Choose folder…", showIcon = false) { onPick(null) }
        }
    }
}

@Composable
private fun QuickPickChip(label: String, showIcon: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showIcon) {
                Icon(
                    Icons.Default.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
