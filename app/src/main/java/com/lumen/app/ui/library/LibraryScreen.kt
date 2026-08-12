package com.lumen.app.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.lumen.app.data.db.dao.FolderStatsRow
import com.lumen.app.data.db.entity.BookmarkEntity
import com.lumen.app.data.db.entity.DocumentEntity
import com.lumen.app.domain.model.DocumentTitles
import com.lumen.app.domain.model.LibraryCounts
import com.lumen.app.domain.model.indexWarningLine
import com.lumen.app.ui.common.PdfThumbnail
import com.lumen.app.ui.common.folderDisplayName
import com.lumen.app.ui.common.quantity
import com.lumen.app.ui.theme.Terracotta
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel = hiltViewModel(),
    onOpenDocument: (uri: String, filename: String, page: Int) -> Unit = { _, _, _ -> },
) {
    val documentsOrNull by viewModel.documents.collectAsState()
    val foldersOrNull by viewModel.folders.collectAsState()
    val isContentLoaded = documentsOrNull != null && foldersOrNull != null
    val documents = documentsOrNull.orEmpty()
    val folders = foldersOrNull.orEmpty()
    val isIndexing by viewModel.isIndexing.collectAsState()
    val lostPermissionFolders by viewModel.lostPermissionFolders.collectAsState()
    val folderStats by viewModel.folderStats.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val bookmarkCounts by viewModel.bookmarkCounts.collectAsState()
    val customTitles by viewModel.customTitles.collectAsState()
    var renameTarget by remember { mutableStateOf<DocumentEntity?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var gridMode by rememberSaveable { mutableStateOf(true) }
    var bookmarkedOnly by rememberSaveable { mutableStateOf(false) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { viewModel.addFolder(it) }
    }

    LibraryDocumentSheetHost(viewModel = viewModel, onOpenDocument = onOpenDocument)

    renameTarget?.let { doc ->
        RenameDocumentDialog(
            currentTitle = DocumentTitles.displayTitle(customTitles[doc.uri], doc.derivedTitle, doc.filename),
            hasCustomTitle = customTitles.containsKey(doc.uri),
            onSave = { newTitle ->
                viewModel.renameDocument(doc.uri, newTitle)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
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

            if (lostPermissionFolders.isNotEmpty()) {
                LostAccessBanner(folderCount = lostPermissionFolders.size)
            }

            if (!isContentLoaded) {
                // First load still in flight — render nothing rather than flashing
                // the "No folders added yet" state at a populated library.
            } else if (folders.isEmpty() && documents.isEmpty()) {
                LibraryEmptyState(onAdd = { folderPickerLauncher.launch(null) })
            } else {
                IndexHealthCard(
                    documents = documents,
                    folderStats = folderStats,
                    onReindexFolder = { viewModel.reindexFolder(it) },
                    onOpenDocument = { doc -> onOpenDocument(doc.uri, doc.filename, 0) },
                    onRetryDocument = { viewModel.retryDocument(it) },
                )

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (documents.isNotEmpty()) {
                        // @spec LIB-SORT-001
                        val visibleDocuments = sortLibrary(
                            if (bookmarkedOnly) {
                                documents.filter { (bookmarkCounts[it.uri] ?: 0) > 0 }
                            } else {
                                documents
                            },
                            sortOrder,
                        )
                        libraryDocumentsItems(
                            visibleDocuments = visibleDocuments,
                            bookmarkCounts = bookmarkCounts,
                            customTitles = customTitles,
                            bookmarkedOnly = bookmarkedOnly,
                            gridMode = gridMode,
                            sortOrder = sortOrder,
                            onToggleBookmarkedOnly = { bookmarkedOnly = !bookmarkedOnly },
                            onToggleGrid = { gridMode = !gridMode },
                            onSetSortOrder = { viewModel.setSortOrder(it) },
                            onTapDocument = { viewModel.showDocumentDetail(it) },
                            onLongPressDocument = { renameTarget = it },
                            onRetryDocument = { viewModel.retryDocument(it) },
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = isIndexing,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            IndexingBottomBar()
        }
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (isIndexing) 70.dp else 0.dp))
    }
}

@Composable
private fun IndexingBottomBar() {
    val pulse = rememberInfiniteTransition(label = "indexing-pulse")
    val alpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(800), repeatMode = RepeatMode.Reverse),
        label = "alpha",
    )
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = alpha), RoundedCornerShape(20.dp))
            )
            Text("Indexing in background…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

// Collapsed one-line summary that expands into the index-health detail. The
// first screenful of the Library shows documents, not statistics; problem
// files are actionable here, beside the counts that reference them (this card
// replaces the former Error Center and Words tile).
// @spec LIB-CNT-001, LIB-CNT-002, LIB-CNT-006, LIB-HLTH-001, LIB-HLTH-005
@Composable
internal fun IndexHealthCard(
    documents: List<DocumentEntity>,
    folderStats: List<FolderStatsRow>,
    onReindexFolder: (String) -> Unit,
    onOpenDocument: (DocumentEntity) -> Unit,
    onRetryDocument: (DocumentEntity) -> Unit,
) {
    val counts = LibraryCounts(
        total = documents.size,
        indexed = documents.count { it.status == DocumentEntity.STATUS_INDEXED },
        failed = documents.count {
            it.status == DocumentEntity.STATUS_ENCRYPTED || it.status == DocumentEntity.STATUS_ERROR
        },
        pending = documents.count {
            it.status == DocumentEntity.STATUS_PENDING || it.status == DocumentEntity.STATUS_INDEXING
        },
    )
    // @spec LIB-CNT-004 — the empty-library state replaces the card entirely.
    val sentence = counts.summarySentence() ?: return
    val encrypted = documents.filter { it.status == DocumentEntity.STATUS_ENCRYPTED }
    val errored = documents.filter { it.status == DocumentEntity.STATUS_ERROR }
    var expanded by rememberSaveable { mutableStateOf(false) }

    Surface(
        onClick = { expanded = !expanded },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = sentence,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // @spec LIB-HLTH-002
                    indexWarningLine(encrypted.size, errored.size)?.let { warning ->
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = Terracotta,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = warning,
                                style = MaterialTheme.typography.bodySmall,
                                color = Terracotta,
                            )
                        }
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse index details" else "Expand index details",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                // @spec LIB-HLTH-003
                folderStats.forEach { stat ->
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stat.treeUri.takeIf { it.isNotBlank() }
                                    ?.let { folderDisplayName(Uri.parse(it)) } ?: "Documents",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "${quantity(stat.files, "file")} · ${quantity(stat.pages, "page")} · " +
                                    "${stat.ocrPages} read via OCR",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onReindexFolder(stat.treeUri) }) {
                            Text("Re-index", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                // @spec LIB-HLTH-004
                (encrypted + errored).forEach { doc ->
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = if (doc.status == DocumentEntity.STATUS_ENCRYPTED) Icons.Default.Lock else Icons.Default.Warning,
                            contentDescription = null,
                            tint = Terracotta,
                            modifier = Modifier.size(18.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = doc.filename,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (doc.status == DocumentEntity.STATUS_ENCRYPTED) {
                                    "Password-protected — can't be indexed yet"
                                } else {
                                    "Indexing failed"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (doc.status == DocumentEntity.STATUS_ENCRYPTED) {
                            TextButton(onClick = { onOpenDocument(doc) }) {
                                Text("Enter password", color = Terracotta, fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            TextButton(onClick = { onRetryDocument(doc) }) {
                                Text("Retry", color = Terracotta, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatCount(n: Int): String = when {
    n >= 1_000_000 -> DecimalFormat("0.0M").format(n / 1_000_000.0)
    n >= 1_000 -> DecimalFormat("0.0k").format(n / 1_000.0)
    else -> n.toString()
}

// Primary line = display title; the raw filename survives as a middle-ellipsized
// caption (the suffix is the distinguishing part of generated names). Long-press
// renames.
// @spec LIB-TTL-001, LIB-TTL-003, LIB-TTL-004
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocumentRow(
    doc: DocumentEntity,
    displayTitle: String,
    bookmarkCount: Int,
    onRetry: (() -> Unit)?,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
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
                    Text(text = displayTitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (displayTitle != doc.filename) {
                        Text(
                            text = doc.filename,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                        )
                    }
                    Text(
                        text = statusLabel(doc),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (doc.status == DocumentEntity.STATUS_ERROR)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (bookmarkCount > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.padding(start = 6.dp),
                    ) {
                        Icon(
                            Icons.Default.Bookmark,
                            contentDescription = "$bookmarkCount bookmarks",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            "$bookmarkCount",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
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

// @spec LIB-TTL-001, LIB-TTL-003, LIB-TTL-004
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocumentGridCard(
    doc: DocumentEntity,
    displayTitle: String,
    bookmarkCount: Int,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val statusColor = when (doc.status) {
        DocumentEntity.STATUS_INDEXED -> MaterialTheme.colorScheme.primary
        DocumentEntity.STATUS_ERROR -> MaterialTheme.colorScheme.error
        DocumentEntity.STATUS_ENCRYPTED -> Terracotta
        else -> MaterialTheme.colorScheme.outline
    }
    Surface(
        modifier = modifier.combinedClickable(onClick = onTap, onLongClick = onLongPress),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(88.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                PdfThumbnail(
                    uriString = doc.uri,
                    pageIndex = 0,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp)),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(8.dp)
                        .background(statusColor, RoundedCornerShape(20.dp))
                )
                if (bookmarkCount > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    ) {
                        Icon(
                            Icons.Default.Bookmark,
                            contentDescription = "$bookmarkCount bookmarks",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(11.dp),
                        )
                        Text(
                            "$bookmarkCount",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = "${doc.pageCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = displayTitle,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (displayTitle != doc.filename) {
                Text(
                    text = doc.filename,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
        }
    }
}

private fun statusLabel(doc: DocumentEntity): String {
    val base = when (doc.status) {
        DocumentEntity.STATUS_INDEXED -> quantity(doc.pageCount, "page")
        DocumentEntity.STATUS_INDEXING -> "Indexing…"
        DocumentEntity.STATUS_ENCRYPTED -> "Encrypted, cannot index"
        DocumentEntity.STATUS_ERROR -> "Failed, tap to retry"
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
    displayTitle: String,
    ocrPageCount: Int,
    bookmarks: List<BookmarkEntity>,
    onReindex: () -> Unit,
    onOpenPdf: () -> Unit,
    onOpenAtPage: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
    ) {
        Text(
            text = displayTitle,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (displayTitle != doc.filename) {
            Text(
                text = doc.filename,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
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

        if (bookmarks.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text(
                "Bookmarks (${bookmarks.size})",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            val dateFormat = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
            bookmarks.forEach { bm ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenAtPage(bm.pageNumber) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            "p. ${bm.pageNumber + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        bm.note?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            dateFormat.format(java.util.Date(bm.createdAt)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                onClick = onOpenPdf,
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        "Open PDF",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
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
internal fun LibraryEmptyState(onAdd: () -> Unit) {
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

// ── Shared with the merged Documents screen ─────────────────────────────────
// One definition per behavior: the LIB specs hold on every surface that shows
// library content because the surfaces render these, not copies of them.

@Composable
internal fun LostAccessBanner(folderCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Terracotta.copy(alpha = 0.15f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = Terracotta, modifier = Modifier.size(18.dp))
        // Lost-permission documents stay in the indexed bucket (still
        // searchable); this banner, not the count model, carries the condition.
        // @spec LIB-CNT-005
        Text(
            text = "${quantity(folderCount, "folder")} " +
                "lost access — remove and re-add in Settings › Data.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
    }
}

// Prefilled with the current display title; Save with blank input resets to
// automatic, as does the explicit reset action.
// @spec LIB-TTL-004
@Composable
internal fun RenameDocumentDialog(
    currentTitle: String,
    hasCustomTitle: Boolean,
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf(currentTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename document") },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.take(120) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Only the name shown in Lumen changes — the file itself is untouched.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(input) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (hasCustomTitle) {
                    TextButton(onClick = { onSave(null) }) { Text("Reset to automatic") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** The document detail bottom sheet, hosted by any screen showing library rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryDocumentSheetHost(
    viewModel: LibraryViewModel,
    onOpenDocument: (uri: String, filename: String, page: Int) -> Unit,
) {
    val selectedDocument by viewModel.selectedDocument.collectAsState()
    val selectedDocOcrPages by viewModel.selectedDocOcrPages.collectAsState()
    val selectedDocBookmarks by viewModel.selectedDocBookmarks.collectAsState()
    val customTitles by viewModel.customTitles.collectAsState()
    if (selectedDocument != null) {
        ModalBottomSheet(onDismissRequest = { viewModel.hideDocumentDetail() }) {
            val doc = selectedDocument!!
            DocumentDetailSheet(
                doc = doc,
                displayTitle = DocumentTitles.displayTitle(customTitles[doc.uri], doc.derivedTitle, doc.filename),
                ocrPageCount = selectedDocOcrPages,
                bookmarks = selectedDocBookmarks,
                onReindex = {
                    // Re-index THIS document only: reset its status and enqueue a
                    // non-force pass, which skips unchanged neighbours.
                    selectedDocument?.let { viewModel.retryDocument(it) }
                    viewModel.hideDocumentDetail()
                },
                onOpenPdf = {
                    val doc = selectedDocument ?: return@DocumentDetailSheet
                    onOpenDocument(doc.uri, doc.filename, 0)
                    viewModel.hideDocumentDetail()
                },
                onOpenAtPage = { page ->
                    val doc = selectedDocument ?: return@DocumentDetailSheet
                    onOpenDocument(doc.uri, doc.filename, page)
                    viewModel.hideDocumentDetail()
                },
                onDismiss = { viewModel.hideDocumentDetail() },
            )
        }
    }
}

/** The documents list: header with count + sort/bookmark/grid controls, then
 *  rows or a two-column grid. */
// @spec LIB-SORT-001
internal fun LazyListScope.libraryDocumentsItems(
    visibleDocuments: List<DocumentEntity>,
    bookmarkCounts: Map<String, Int>,
    customTitles: Map<String, String>,
    bookmarkedOnly: Boolean,
    gridMode: Boolean,
    sortOrder: LibrarySortOrder,
    onToggleBookmarkedOnly: () -> Unit,
    onToggleGrid: () -> Unit,
    onSetSortOrder: (LibrarySortOrder) -> Unit,
    onTapDocument: (DocumentEntity) -> Unit,
    onLongPressDocument: (DocumentEntity) -> Unit,
    onRetryDocument: (DocumentEntity) -> Unit,
) {
    item {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (bookmarkedOnly) {
                    "Bookmarked (${visibleDocuments.size})"
                } else {
                    "Documents (${visibleDocuments.size})"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    var showSortMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Sort,
                            contentDescription = "Sort documents",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false },
                    ) {
                        sortOptionLabels.forEach { (order, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                leadingIcon = {
                                    if (order == sortOrder) {
                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                    } else {
                                        Spacer(Modifier.size(18.dp))
                                    }
                                },
                                onClick = {
                                    showSortMenu = false
                                    onSetSortOrder(order)
                                },
                            )
                        }
                    }
                }
                IconButton(onClick = onToggleBookmarkedOnly) {
                    Icon(
                        imageVector = if (bookmarkedOnly) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = if (bookmarkedOnly) {
                            "Show all documents"
                        } else {
                            "Show only bookmarked documents"
                        },
                        tint = if (bookmarkedOnly) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                IconButton(onClick = onToggleGrid) {
                    Icon(
                        imageVector = if (gridMode) Icons.Default.ViewAgenda else Icons.Default.GridView,
                        contentDescription = if (gridMode) "Switch to list view" else "Switch to grid view",
                    )
                }
            }
        }
    }
    if (bookmarkedOnly && visibleDocuments.isEmpty()) {
        item {
            Text(
                "No bookmarked documents yet. Open a PDF and tap the bookmark icon in the top bar.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
    if (!gridMode) {
        items(visibleDocuments, key = { it.id }) { doc ->
            DocumentRow(
                doc = doc,
                displayTitle = DocumentTitles.displayTitle(customTitles[doc.uri], doc.derivedTitle, doc.filename),
                bookmarkCount = bookmarkCounts[doc.uri] ?: 0,
                onRetry = if (doc.status == DocumentEntity.STATUS_ERROR) {
                    { onRetryDocument(doc) }
                } else null,
                onTap = { onTapDocument(doc) },
                onLongPress = { onLongPressDocument(doc) },
            )
        }
    } else {
        items(visibleDocuments.chunked(2), key = { it.first().id }) { chunk ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                chunk.forEach { doc ->
                    DocumentGridCard(
                        doc = doc,
                        displayTitle = DocumentTitles.displayTitle(customTitles[doc.uri], doc.derivedTitle, doc.filename),
                        bookmarkCount = bookmarkCounts[doc.uri] ?: 0,
                        modifier = Modifier.weight(1f),
                        onTap = { onTapDocument(doc) },
                        onLongPress = { onLongPressDocument(doc) },
                    )
                }
                if (chunk.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
