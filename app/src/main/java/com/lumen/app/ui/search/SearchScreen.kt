package com.lumen.app.ui.search

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.lumen.app.data.db.entity.DocumentEntity
import com.lumen.app.domain.model.SearchFilters
import com.lumen.app.domain.model.SearchResult
import com.lumen.app.domain.model.SortOrder
import com.lumen.app.ui.common.PdfThumbnail
import com.lumen.app.ui.icons.LumenBrandIcon
import com.lumen.app.ui.theme.AmberAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    onResultClick: (uri: String, page: Int, filename: String, keyword: String, occurrence: Int) -> Unit = { _, _, _, _, _ -> },
    onOpenLibrary: () -> Unit = {},
) {
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val isTruncated by viewModel.isTruncated.collectAsState()
    val indexedCount by viewModel.indexedCount.collectAsState()
    val isIndexing by viewModel.isIndexing.collectAsState()
    val searchHistory by viewModel.searchHistory.collectAsState()
    val recentDocuments by viewModel.recentDocuments.collectAsState()
    val availableFolders by viewModel.availableFolders.collectAsState()
    val filters by viewModel.filters.collectAsState()

    var isSearchFieldFocused by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }

    val showHistory = isSearchFieldFocused && query.isBlank() && searchHistory.isNotEmpty()
    val activeFilterCount = (if (filters.folderIds.isNotEmpty()) 1 else 0) +
        (if (filters.ocrOnly) 1 else 0) +
        (if (filters.sortOrder != SortOrder.RELEVANCE) 1 else 0)

    if (showFilterSheet) {
        SearchFilterSheet(
            filters = filters,
            availableFolders = availableFolders,
            onFiltersChange = { viewModel.filters.value = it },
            onDismiss = { showFilterSheet = false },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        SearchHeader(
            query = query,
            indexedCount = indexedCount,
            activeFilterCount = activeFilterCount,
            onQueryChange = { viewModel.query.value = it },
            onClearQuery = { viewModel.query.value = "" },
            onFocusChange = { isSearchFieldFocused = it },
            onFilterClick = { showFilterSheet = true },
        )

        ActiveFiltersRow(
            filters = filters,
            availableFolders = availableFolders,
            onRemoveFolder = { folderId ->
                viewModel.filters.value = filters.copy(folderIds = filters.folderIds - folderId)
            },
            onToggleOcrOnly = {
                viewModel.filters.value = filters.copy(ocrOnly = !filters.ocrOnly)
            },
            onResetSort = {
                viewModel.filters.value = filters.copy(sortOrder = SortOrder.RELEVANCE)
            },
            onClearAll = { viewModel.filters.value = SearchFilters() },
        )

        if (isIndexing) {
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

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                showHistory -> SearchHistoryList(
                    history = searchHistory,
                    onSelect = { viewModel.query.value = it },
                    onRemove = { viewModel.removeHistoryItem(it) },
                    onClearAll = { viewModel.clearHistory() },
                )
                query.trim().length < 2 -> SearchEmptyState(
                    indexedCount = indexedCount,
                    recentSearches = searchHistory,
                    recentDocuments = recentDocuments,
                    onSelectRecentSearch = { viewModel.query.value = it },
                    onOpenDocument = { doc -> onResultClick(doc.uri, 0, doc.filename, "", 0) },
                    onOpenLibrary = onOpenLibrary,
                )
                isSearching -> SkeletonResultList()
                results.isEmpty() -> NoResultsState(
                    query = query,
                    onOpenLibrary = onOpenLibrary,
                    onClearQuery = { viewModel.query.value = "" },
                )
                else -> ResultList(
                    query = query,
                    results = results,
                    isTruncated = isTruncated,
                    onResultClick = { uri, page, filename, occurrence ->
                        viewModel.onResultSelected(query.trim())
                        onResultClick(uri, page, filename, query.trim(), occurrence)
                    },
                )
            }
        }
    }
}

@Composable
private fun SearchHeader(
    query: String,
    indexedCount: Int,
    activeFilterCount: Int,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onFilterClick: () -> Unit,
) {
    Column(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Lumen",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            LumenBrandIcon(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(9.dp))
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .shadow(8.dp, RoundedCornerShape(28.dp)),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
            ) {
                TextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .onFocusChanged { onFocusChange(it.isFocused) },
                    placeholder = { Text("Search your PDFs…") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = if (query.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingIcon = if (query.isNotEmpty()) {
                        {
                            IconButton(onClick = onClearQuery) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        width = if (activeFilterCount > 0) 1.dp else 0.dp,
                        color = if (activeFilterCount > 0) AmberAccent.copy(alpha = 0.55f) else Color.Transparent,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .clickable(onClick = onFilterClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.FilterList,
                    contentDescription = "Filters",
                    tint = if (activeFilterCount > 0) AmberAccent
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                if (activeFilterCount > 0) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .background(AmberAccent, CircleShape)
                            .align(Alignment.TopEnd)
                            .padding(end = 2.dp, top = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = activeFilterCount.toString(),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                            color = Color.White,
                        )
                    }
                }
            }
        }

        if (indexedCount > 0) {
            Text(
                text = "Searching across $indexedCount ${if (indexedCount == 1) "PDF" else "PDFs"} · offline",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        } else {
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SearchHistoryList(
    history: List<String>,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearAll: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Recent",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Clear all",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onClearAll),
                )
            }
        }
        items(history, key = { it }) { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(item) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text(item, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { onRemove(item) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Clear, contentDescription = "Remove", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}

@Composable
private fun SkeletonResultList() {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse),
        label = "alpha",
    )
    val shimmerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(7) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Box(modifier = Modifier.fillMaxWidth(0.55f).height(14.dp).background(shimmerColor, RoundedCornerShape(4.dp)))
                    Box(modifier = Modifier.width(40.dp).height(14.dp).background(shimmerColor, RoundedCornerShape(4.dp)))
                }
                Spacer(Modifier.height(8.dp))
                Box(modifier = Modifier.fillMaxWidth().height(11.dp).background(shimmerColor, RoundedCornerShape(4.dp)))
                Spacer(Modifier.height(5.dp))
                Box(modifier = Modifier.fillMaxWidth(0.75f).height(11.dp).background(shimmerColor, RoundedCornerShape(4.dp)))
                Spacer(Modifier.height(5.dp))
                Box(modifier = Modifier.fillMaxWidth(0.4f).height(10.dp).background(shimmerColor, RoundedCornerShape(4.dp)))
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}

@Composable
private fun ResultList(
    query: String,
    results: List<SearchResult>,
    isTruncated: Boolean,
    onResultClick: (uri: String, page: Int, filename: String, occurrence: Int) -> Unit,
) {
    val label = if (results.size == 1) "1 result" else "${results.size} results"
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        itemsIndexed(results, key = { _, item -> item.lineId }) { index, result ->
            androidx.compose.animation.AnimatedVisibility(
                visible = true,
                enter = fadeIn(animationSpec = tween(220, delayMillis = index * 20)) +
                    slideInHorizontally(animationSpec = tween(220, delayMillis = index * 20), initialOffsetX = { it / 4 }),
            ) {
                ResultRow(
                    query = query,
                    result = result,
                    onClick = { onResultClick(result.uri, result.pageNumber, result.filename, result.occurrenceOnPage) },
                )
            }
        }
        if (isTruncated) {
            item {
                Text(
                    text = "Showing first 200 results, try a more specific query",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ResultRow(
    query: String,
    result: SearchResult,
    onClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var showMenu by remember { mutableStateOf(false) }

    Box {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            shape = RoundedCornerShape(14.dp),
            tonalElevation = 2.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onClick()
                        },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showMenu = true
                        },
                    )
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 48.dp, height = 64.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    PdfThumbnail(
                        uriString = result.uri,
                        pageIndex = result.pageNumber,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp)),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = result.filename,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (result.isFilenameMatch) "name" else "p. ${result.pageNumber + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = AmberAccent,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .background(AmberAccent.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = if (result.isFilenameMatch) {
                            androidx.compose.ui.text.AnnotatedString(result.snippet)
                        } else {
                            buildHighlightedSnippet(
                                query = query,
                                snippet = result.snippet,
                                highlightColor = AmberAccent.copy(alpha = 0.18f),
                                highlightTextColor = MaterialTheme.colorScheme.onBackground,
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )

                    if (result.folderName.isNotEmpty()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = result.folderName,
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (result.isOcr) {
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = "OCR",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text("Open at page ${result.pageNumber + 1}") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(18.dp)) },
                onClick = {
                    showMenu = false
                    onClick()
                },
            )
            DropdownMenuItem(
                text = { Text("Copy snippet") },
                leadingIcon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp)) },
                onClick = {
                    showMenu = false
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Lumen snippet", result.snippet))
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(context, "Snippet copied", Toast.LENGTH_SHORT).show()
                    }
                },
            )
            DropdownMenuItem(
                text = { Text("Share") },
                leadingIcon = { Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp)) },
                onClick = {
                    showMenu = false
                    val text = "${result.filename} · p.${result.pageNumber + 1}\n\n${result.snippet}"
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            },
                            "Share snippet"
                        )
                    )
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SearchFilterSheet(
    filters: SearchFilters,
    availableFolders: Set<android.net.Uri>,
    onFiltersChange: (SearchFilters) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Search filters", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            if (availableFolders.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Folder", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        availableFolders.forEach { uri ->
                            val folderId = folderIdFromTreeUri(uri)
                            val label = uri.lastPathSegment ?: uri.toString()
                            FilterChip(
                                selected = folderId in filters.folderIds,
                                onClick = {
                                    val newSet = filters.folderIds.toMutableSet()
                                    if (folderId in newSet) newSet.remove(folderId) else newSet.add(folderId)
                                    onFiltersChange(filters.copy(folderIds = newSet))
                                },
                                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("OCR results only", style = MaterialTheme.typography.bodyMedium)
                    Text("Show only scanned pages", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = filters.ocrOnly,
                    onCheckedChange = { onFiltersChange(filters.copy(ocrOnly = it)) },
                )
            }

            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Sort by", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SortOrder.entries.forEach { order ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onFiltersChange(filters.copy(sortOrder = order)) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = filters.sortOrder == order,
                            onClick = { onFiltersChange(filters.copy(sortOrder = order)) },
                        )
                        Text(order.displayName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (filters != SearchFilters()) {
                OutlinedButton(
                    onClick = { onFiltersChange(SearchFilters()) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Reset filters")
                }
            }
        }
    }
}

private fun folderIdFromTreeUri(uri: Uri): String =
    runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrDefault(uri.toString())

private fun folderLabel(folderId: String, availableFolders: Set<Uri>): String =
    availableFolders.firstOrNull { folderIdFromTreeUri(it) == folderId }?.lastPathSegment ?: folderId

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveFiltersRow(
    filters: SearchFilters,
    availableFolders: Set<Uri>,
    onRemoveFolder: (String) -> Unit,
    onToggleOcrOnly: () -> Unit,
    onResetSort: () -> Unit,
    onClearAll: () -> Unit,
) {
    if (filters == SearchFilters()) return

    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        filters.folderIds.forEach { folderId ->
            FilterChip(
                selected = true,
                onClick = { onRemoveFolder(folderId) },
                label = { Text("Folder: ${folderLabel(folderId, availableFolders)}") },
                leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp)) },
            )
        }

        if (filters.ocrOnly) {
            FilterChip(
                selected = true,
                onClick = onToggleOcrOnly,
                label = { Text("OCR only") },
            )
        }

        if (filters.sortOrder != SortOrder.RELEVANCE) {
            FilterChip(
                selected = true,
                onClick = onResetSort,
                label = { Text("Sort: ${filters.sortOrder.displayName}") },
            )
        }

        FilterChip(
            selected = false,
            onClick = onClearAll,
            label = { Text("Clear all") },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchEmptyState(
    indexedCount: Int,
    recentSearches: List<String>,
    recentDocuments: List<DocumentEntity>,
    onSelectRecentSearch: (String) -> Unit,
    onOpenDocument: (DocumentEntity) -> Unit,
    onOpenLibrary: () -> Unit,
) {
    // Nothing indexed yet — keep the original onboarding-style prompt.
    if (indexedCount == 0) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(96.dp))
            Box(
                modifier = Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text("Nothing indexed yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Add a folder of PDFs to start searching offline.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onOpenLibrary) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(8.dp))
                Text("Add a folder")
            }
        }
        return
    }

    // Has content — make the home screen useful: recent searches + recent documents.
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        if (recentSearches.isNotEmpty()) {
            item {
                Text(
                    "Recent searches",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                )
            }
            item {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    recentSearches.take(8).forEach { term ->
                        Surface(
                            onClick = { onSelectRecentSearch(term) },
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(6.dp))
                                Text(term, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        if (recentDocuments.isNotEmpty()) {
            item {
                Text(
                    "Recently indexed",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                )
            }
            items(recentDocuments, key = { it.id }) { doc ->
                RecentDocumentRow(doc = doc, onClick = { onOpenDocument(doc) })
            }
        }

        item {
            Text(
                text = "Tip: Use multiple words for AND matching — \"climate policy 2024\" finds lines containing all three terms.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            )
        }
    }
}

@Composable
private fun RecentDocumentRow(doc: DocumentEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 48.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            PdfThumbnail(
                uriString = doc.uri,
                pageIndex = 0,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = doc.filename,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${doc.pageCount} ${if (doc.pageCount == 1) "page" else "pages"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoResultsState(query: String, onOpenLibrary: () -> Unit, onClearQuery: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        Text("No results for “$query”", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text("Try a different word, or check that the folder is indexed", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onClearQuery) { Text("Clear search") }
            Button(onClick = onOpenLibrary) { Text("Open library") }
        }
    }
}

private fun buildHighlightedSnippet(
    query: String,
    snippet: String,
    highlightColor: Color,
    highlightTextColor: Color,
): androidx.compose.ui.text.AnnotatedString = buildAnnotatedString {
    append("“…")
    if (snippet.isBlank()) {
        append("…”")
        return@buildAnnotatedString
    }
    // Parse <b>...</b> markers from FTS4 snippet
    var i = 0
    while (i < snippet.length) {
        val bOpen = snippet.indexOf("<b>", i)
        if (bOpen == -1) {
            append(snippet.substring(i))
            break
        }
        if (bOpen > i) append(snippet.substring(i, bOpen))
        val bClose = snippet.indexOf("</b>", bOpen)
        if (bClose == -1) {
            append(snippet.substring(bOpen))
            break
        }
        withStyle(SpanStyle(color = highlightTextColor, fontWeight = FontWeight.SemiBold, background = highlightColor)) {
            append(snippet.substring(bOpen + 3, bClose))
        }
        i = bClose + 4
    }
    append("…”")
}
