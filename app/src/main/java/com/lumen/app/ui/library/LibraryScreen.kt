package com.lumen.app.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lumen.app.data.db.entity.DocumentEntity
import com.lumen.app.ui.theme.AmberAccent
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(viewModel: LibraryViewModel = hiltViewModel()) {
    val documents by viewModel.documents.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val isIndexing by viewModel.isIndexing.collectAsState()
    val lostPermissionFolders by viewModel.lostPermissionFolders.collectAsState()
    val totalPages by viewModel.totalPages.collectAsState()
    val totalWords by viewModel.totalWords.collectAsState()
    val ocrPages by viewModel.ocrPages.collectAsState()
    val selectedDocument by viewModel.selectedDocument.collectAsState()
    val selectedDocOcrPages by viewModel.selectedDocOcrPages.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { viewModel.addFolder(it) }
    }

    // Document detail bottom sheet
    if (selectedDocument != null) {
        ModalBottomSheet(onDismissRequest = { viewModel.hideDocumentDetail() }) {
            DocumentDetailSheet(
                doc = selectedDocument!!,
                ocrPageCount = selectedDocOcrPages,
                onReindex = {
                    val treeUri = selectedDocument!!.treeUri
                        .takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
                    if (treeUri != null) viewModel.reindexFolder(treeUri)
                    viewModel.hideDocumentDetail()
                },
                onDismiss = { viewModel.hideDocumentDetail() },
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Library",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Surface(
                    onClick = { folderPickerLauncher.launch(null) },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add Folder", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }

            AnimatedVisibility(visible = isIndexing, enter = fadeIn(), exit = fadeOut()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    )
                    Text("Indexing…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }

            if (lostPermissionFolders.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AmberAccent.copy(alpha = 0.15f))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = AmberAccent, modifier = Modifier.size(18.dp))
                    Text(
                        text = "${lostPermissionFolders.size} folder${if (lostPermissionFolders.size > 1) "s" else ""} " +
                            "lost access — remove and re-add using + Add Folder.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (folders.isEmpty() && documents.isEmpty()) {
                LibraryEmptyState(onAdd = { folderPickerLauncher.launch(null) })
            } else {
                LibraryStats(
                    documents = documents,
                    folderCount = folders.size,
                    totalPages = totalPages,
                    totalWords = totalWords,
                    ocrPages = ocrPages,
                )

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (folders.isNotEmpty()) {
                        item { SectionHeader("Indexed Folders") }
                        items(folders.toList(), key = { it.toString() }) { uri ->
                            SwipeableFolderRow(
                                uri = uri,
                                hasLostPermission = uri in lostPermissionFolders,
                                onRemove = {
                                    viewModel.removeFolder(uri)
                                    scope.launch { snackbarHostState.showSnackbar("Folder removed") }
                                },
                                onReindex = { viewModel.reindexFolder(uri) },
                            )
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                    }
                    if (documents.isNotEmpty()) {
                        item { SectionHeader("Documents (${documents.size})") }
                        items(documents, key = { it.id }) { doc ->
                            DocumentRow(
                                doc = doc,
                                onRetry = if (doc.status == DocumentEntity.STATUS_ERROR) {
                                    { viewModel.retryDocument(doc) }
                                } else null,
                                onTap = { viewModel.showDocumentDetail(doc) },
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableFolderRow(
    uri: Uri,
    hasLostPermission: Boolean,
    onRemove: () -> Unit,
    onReindex: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val dismissState = rememberSwipeToDismissBoxState()

    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onRemove()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove folder",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(end = 20.dp),
                )
            }
        },
    ) {
        FolderRow(uri = uri, hasLostPermission = hasLostPermission, onRemove = onRemove, onReindex = onReindex)
    }
}

@Composable
private fun LibraryStats(
    documents: List<DocumentEntity>,
    folderCount: Int,
    totalPages: Int,
    totalWords: Int,
    ocrPages: Int,
) {
    val indexed = documents.count { it.status == DocumentEntity.STATUS_INDEXED }
    val pending = documents.count {
        it.status == DocumentEntity.STATUS_PENDING || it.status == DocumentEntity.STATUS_INDEXING
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatItem(count = indexed, label = "Indexed")
                StatDivider()
                StatItem(count = folderCount, label = "Folders")
                StatDivider()
                StatItem(count = pending, label = "Pending", useAccent = pending > 0)
            }

            if (totalPages > 0) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    MiniStat(value = formatCount(totalPages), label = "Pages")
                    MiniStat(value = formatCount(totalWords), label = "Words")
                    MiniStat(
                        value = if (totalPages > 0) "${(ocrPages * 100 / totalPages)}%" else "0%",
                        label = "OCR",
                    )
                }
            }
        }
    }
}

@Composable
private fun StatItem(count: Int, label: String, useAccent: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = if (useAccent) AmberAccent else MaterialTheme.colorScheme.onSurface,
        )
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatDivider() {
    Box(modifier = Modifier.width(1.dp).height(40.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
private fun MiniStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatCount(n: Int): String = when {
    n >= 1_000_000 -> DecimalFormat("0.0M").format(n / 1_000_000.0)
    n >= 1_000 -> DecimalFormat("0.0k").format(n / 1_000.0)
    else -> n.toString()
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun FolderRow(
    uri: Uri,
    hasLostPermission: Boolean,
    onRemove: () -> Unit,
    onReindex: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 1.dp,
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (hasLostPermission) Icons.Default.Warning else Icons.Default.FolderOpen,
                contentDescription = null,
                tint = if (hasLostPermission) AmberAccent else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    text = uri.lastPathSegment ?: uri.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hasLostPermission) {
                    Text(
                        text = "Permission lost — remove and re-add",
                        style = MaterialTheme.typography.labelSmall,
                        color = AmberAccent,
                    )
                }
            }
            if (!hasLostPermission) {
                IconButton(onClick = onReindex) {
                    Icon(Icons.Default.Refresh, contentDescription = "Re-index folder", tint = MaterialTheme.colorScheme.primary)
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "Remove folder", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun DocumentRow(doc: DocumentEntity, onRetry: (() -> Unit)?, onTap: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clickable(onClick = onTap),
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.PictureAsPdf,
                    contentDescription = null,
                    tint = when (doc.status) {
                        DocumentEntity.STATUS_INDEXED -> MaterialTheme.colorScheme.primary
                        DocumentEntity.STATUS_ERROR -> MaterialTheme.colorScheme.error
                        DocumentEntity.STATUS_ENCRYPTED -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.outline
                    },
                    modifier = Modifier.size(22.dp),
                )
                Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(text = doc.filename, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = statusLabel(doc),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (doc.status == DocumentEntity.STATUS_ERROR)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onRetry != null) {
                    IconButton(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, contentDescription = "Retry indexing", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (doc.status == DocumentEntity.STATUS_INDEXING) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun statusLabel(doc: DocumentEntity): String {
    val base = when (doc.status) {
        DocumentEntity.STATUS_INDEXED -> "${doc.pageCount} pages"
        DocumentEntity.STATUS_INDEXING -> "Indexing…"
        DocumentEntity.STATUS_ENCRYPTED -> "Encrypted — cannot index"
        DocumentEntity.STATUS_ERROR -> "Failed — tap to retry"
        else -> "Pending"
    }
    val indexedAgo = if (doc.status == DocumentEntity.STATUS_INDEXED && doc.indexedAt != null) {
        val days = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - doc.indexedAt).toInt()
        when {
            days == 0 -> " · indexed today"
            days == 1 -> " · indexed yesterday"
            days < 30 -> " · indexed $days days ago"
            else -> " · indexed ${days / 30}mo ago"
        }
    } else ""
    return base + indexedAgo
}

@Composable
private fun DocumentDetailSheet(
    doc: DocumentEntity,
    ocrPageCount: Int,
    onReindex: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
    ) {
        Text(
            text = doc.filename,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = when (doc.status) {
                DocumentEntity.STATUS_INDEXED -> "Indexed"
                DocumentEntity.STATUS_INDEXING -> "Indexing…"
                DocumentEntity.STATUS_ENCRYPTED -> "Encrypted"
                DocumentEntity.STATUS_ERROR -> "Error"
                else -> "Pending"
            },
            style = MaterialTheme.typography.labelMedium,
            color = when (doc.status) {
                DocumentEntity.STATUS_INDEXED -> MaterialTheme.colorScheme.primary
                DocumentEntity.STATUS_ERROR -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        DetailRow("Pages", doc.pageCount.toString())
        DetailRow("OCR pages", "$ocrPageCount of ${doc.pageCount}")
        DetailRow("File size", formatBytes(doc.sizeBytes))
        if (doc.indexedAt != null) {
            DetailRow("Indexed", java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault()).format(java.util.Date(doc.indexedAt)))
        }
        DetailRow("Added", java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault()).format(java.util.Date(doc.addedAt)))

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                onClick = onReindex,
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text("Re-index", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "Unknown"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> DecimalFormat("0.0").format(bytes / (1024.0 * 1024.0)) + " MB"
}

@Composable
private fun LibraryEmptyState(onAdd: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))
            Text("No folders added yet", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text("Tap + Add Folder to pick a folder full of PDFs", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        }
    }
}
