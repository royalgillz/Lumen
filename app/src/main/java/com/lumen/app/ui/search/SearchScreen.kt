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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
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
import com.lumen.app.data.db.FtsQuerySanitizer
import com.lumen.app.data.db.entity.DocumentEntity
import com.lumen.app.domain.model.DocumentTitles
import com.lumen.app.domain.model.IndexedWithin
import com.lumen.app.domain.model.SearchFilters
import com.lumen.app.domain.model.SearchResult
import com.lumen.app.domain.model.SortOrder
import com.lumen.app.ui.common.PdfThumbnail
import com.lumen.app.ui.common.folderDisplayName
import com.lumen.app.ui.common.quantity
import com.lumen.app.ui.icons.LumenBrandIcon
import com.lumen.app.ui.theme.AmberAccent
import com.lumen.app.ui.theme.Terracotta

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    onResultClick: (uri: String, page: Int, filename: String, keyword: String, occurrence: Int) -> Unit = { _, _, _, _, _ -> },
    onOpenLibrary: () -> Unit = {},
    // The merged Documents screen appends the library body to the idle home and
    // suppresses the "Nothing indexed" prompt (its library empty state covers it).
    // @spec NAV-006
    showNoIndexPrompt: Boolean = true,
    extraIdleContent: (LazyListScope.() -> Unit)? = null,
) {
    val query by viewModel.query.collectAsState()
    val customTitles by viewModel.customTitles.collectAsState()
    val results by viewModel.results.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val isTruncated by viewModel.isTruncated.collectAsState()
    val searchFailed by viewModel.searchFailed.collectAsState()
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
        (if (filters.sortOrder != SortOrder.RELEVANCE) 1 else 0) +
        (if (filters.indexedWithin != IndexedWithin.ANY_TIME) 1 else 0)

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
            onResetIndexedWithin = {
                viewModel.filters.value = filters.copy(indexedWithin = IndexedWithin.ANY_TIME)
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
                // A query that normalizes to nothing (pure punctuation) is an
                // empty query from the index's point of view — idle home, not
                // stale results or a misleading "no results".
                // @spec SEARCH-QRY-003
                query.trim().length < 2 || FtsQuerySanitizer.tokenize(query).isEmpty() ->
                    SearchEmptyState(
                    indexedCount = indexedCount,
                    recentSearches = searchHistory,
                    recentDocuments = recentDocuments,
                    onSelectRecentSearch = { viewModel.query.value = it },
                    onOpenDocument = { doc -> onResultClick(doc.uri, 0, doc.filename, "", 0) },
                    onOpenLibrary = onOpenLibrary,
                    customTitles = customTitles,
                    showNoIndexPrompt = showNoIndexPrompt,
                    extraContent = extraIdleContent,
                )
                searchFailed -> SearchErrorState()
                // Keep stale results visible while a refinement is in flight; the
                // skeleton is only for searches with nothing to show yet.
                isSearching && results.isEmpty() -> SkeletonResultList()
                results.isEmpty() -> NoResultsState(
                    query = query,
                    hasActiveFilters = activeFilterCount > 0,
                    onOpenLibrary = onOpenLibrary,
                    onClearQuery = { viewModel.query.value = "" },
                    onClearFilters = { viewModel.filters.value = SearchFilters() },
                )
                else -> ResultList(
                    query = query,
                    results = results,
                    isTruncated = isTruncated,
                    onResultClick = { result ->
                        viewModel.onResultSelected(query.trim())
                        // Filename matches have no in-text occurrence — opening with a
                        // keyword would make the viewer hunt for a phantom highlight.
                        val keyword = if (result.isFilenameMatch) "" else query.trim()
                        val occurrence = if (result.isFilenameMatch) 0 else result.occurrenceOnPage
                        onResultClick(result.uri, result.pageNumber, result.filename, keyword, occurrence)
                    },
                )
            }
        }
    }
}

@Composable
private fun SearchHeader(
    query: String,
    indexedCount: Int?,
    activeFilterCount: Int,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onFilterClick: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
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
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
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
                        color = if (activeFilterCount > 0) Terracotta.copy(alpha = 0.55f) else Color.Transparent,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .clickable(onClick = onFilterClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.FilterList,
                    contentDescription = "Filters",
                    tint = if (activeFilterCount > 0) Terracotta
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                if (activeFilterCount > 0) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .background(Terracotta, CircleShape)
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

        // Steady-state copy in the interface font: "ready", never an implied
        // activity; the privacy promise in plain words, never "offline".
        // @spec SEARCH-UI-001, SEARCH-UI-002
        if (indexedCount != null && indexedCount > 0) {
            Text(
                text = "${quantity(indexedCount, "PDF")} ready · everything stays on this device",
                style = MaterialTheme.typography.bodySmall,
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

private const val COLLAPSED_PAGE_ROWS = 3

private data class DocGroup(
    val docId: Long,
    val pages: List<SearchResult>,
)

private sealed interface ResultListEntry {
    // LazyColumn key — Long lineIds for rows, prefixed strings for synthetic items,
    // so the two namespaces can never collide.
    val key: Any

    data class FileMatch(val result: SearchResult) : ResultListEntry {
        override val key: Any get() = result.lineId
    }

    data class DocHeader(val group: DocGroup) : ResultListEntry {
        override val key: Any get() = "doc-${group.docId}"
    }

    data class PageRow(val result: SearchResult, val isLastInCard: Boolean) : ResultListEntry {
        override val key: Any get() = result.lineId
    }

    data class ExpandToggle(val docId: Long, val totalPages: Int, val expanded: Boolean) : ResultListEntry {
        override val key: Any get() = "toggle-$docId"
    }
}

// Groups content results by document in order of first appearance (preserving the
// relevance ranking); filename matches stay standalone rows at their original position.
private fun buildResultEntries(
    results: List<SearchResult>,
    expandedDocIds: Set<Long>,
): List<ResultListEntry> {
    val entries = mutableListOf<ResultListEntry>()
    val grouped = LinkedHashMap<Long, MutableList<SearchResult>>()
    for (result in results) {
        if (result.isFilenameMatch) {
            entries.add(ResultListEntry.FileMatch(result))
        } else {
            grouped.getOrPut(result.docId) { mutableListOf() }.add(result)
        }
    }
    for ((docId, pages) in grouped) {
        entries.add(ResultListEntry.DocHeader(DocGroup(docId, pages)))
        val needsToggle = pages.size > COLLAPSED_PAGE_ROWS
        val expanded = docId in expandedDocIds
        val visible = if (needsToggle && !expanded) pages.take(COLLAPSED_PAGE_ROWS) else pages
        visible.forEachIndexed { i, page ->
            entries.add(ResultListEntry.PageRow(page, isLastInCard = !needsToggle && i == visible.lastIndex))
        }
        if (needsToggle) {
            entries.add(ResultListEntry.ExpandToggle(docId, pages.size, expanded))
        }
    }
    return entries
}

@Composable
private fun ResultList(
    query: String,
    results: List<SearchResult>,
    isTruncated: Boolean,
    onResultClick: (SearchResult) -> Unit,
) {
    val label = if (results.size == 1) "1 result" else "${results.size} results"
    // List<Long> rather than Set: autoSaver can round-trip it through the Bundle.
    var expandedDocIds by rememberSaveable { mutableStateOf(listOf<Long>()) }
    val entries = remember(results, expandedDocIds) {
        buildResultEntries(results, expandedDocIds.toSet())
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item(key = "results-count") {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        itemsIndexed(entries, key = { _, entry -> entry.key }) { index, entry ->
            // Cap the stagger: an uncapped index * 20ms left rows scrolled to from
            // deep in the list invisible for seconds after they composed.
            val delay = index.coerceAtMost(10) * 20
            androidx.compose.animation.AnimatedVisibility(
                visible = true,
                enter = fadeIn(animationSpec = tween(220, delayMillis = delay)) +
                    slideInHorizontally(animationSpec = tween(220, delayMillis = delay), initialOffsetX = { it / 4 }),
            ) {
                when (entry) {
                    is ResultListEntry.FileMatch -> ResultRow(
                        query = query,
                        result = entry.result,
                        onClick = { onResultClick(entry.result) },
                    )
                    is ResultListEntry.DocHeader -> DocumentGroupHeader(
                        group = entry.group,
                        onClick = { onResultClick(entry.group.pages.first()) },
                    )
                    is ResultListEntry.PageRow -> PageMatchRow(
                        query = query,
                        result = entry.result,
                        isLastInCard = entry.isLastInCard,
                        onClick = { onResultClick(entry.result) },
                    )
                    is ResultListEntry.ExpandToggle -> ExpandToggleRow(
                        expanded = entry.expanded,
                        totalPages = entry.totalPages,
                        onToggle = {
                            expandedDocIds = if (entry.docId in expandedDocIds) {
                                expandedDocIds - entry.docId
                            } else {
                                expandedDocIds + entry.docId
                            }
                        },
                    )
                }
            }
        }
        if (isTruncated) {
            item(key = "truncated-footer") {
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

@Composable
private fun DocumentGroupHeader(
    group: DocGroup,
    onClick: () -> Unit,
) {
    val first = group.pages.first()
    val totalHits = group.pages.sumOf { it.hitCount }
    val matchesLabel = when {
        totalHits == 1 -> "1 match"
        totalHits > 1 -> "$totalHits matches"
        group.pages.size == 1 -> "1 page"
        else -> "${group.pages.size} pages"
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp),
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 48.dp, height = 64.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                PdfThumbnail(
                    uriString = first.uri,
                    pageIndex = first.pageNumber,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp)),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // @spec SEARCH-UI-006
                    text = first.displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (first.displayTitle != first.filename) {
                    Text(
                        // @spec LIB-TTL-003
                        text = first.filename,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
                if (first.folderName.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = first.folderName,
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = matchesLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Terracotta,
                        modifier = Modifier
                            .background(Terracotta.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    if (group.pages.any { it.isOcr }) {
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
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageMatchRow(
    query: String,
    result: SearchResult,
    isLastInCard: Boolean,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var showMenu by remember { mutableStateOf(false) }

    Box {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = if (isLastInCard) 6.dp else 0.dp),
            shape = if (isLastInCard) {
                RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
            } else {
                RoundedCornerShape(0.dp)
            },
            tonalElevation = 2.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                )
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
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "p. ${result.pageNumber + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = Terracotta,
                        modifier = Modifier
                            .background(Terracotta.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Text(
                        text = buildHighlightedSnippet(
                            snippet = result.snippet,
                            highlights = result.snippetHighlights,
                            highlightColor = AmberAccent.copy(alpha = 0.18f),
                            highlightTextColor = MaterialTheme.colorScheme.onBackground,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (result.hitCount > 1) {
                        Text(
                            text = "×${result.hitCount}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }

        SnippetContextMenu(
            result = result,
            expanded = showMenu,
            onDismiss = { showMenu = false },
            onOpen = onClick,
        )
    }
}

@Composable
private fun ExpandToggleRow(
    expanded: Boolean,
    totalPages: Int,
    onToggle: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
        shape = RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp),
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            )
            Text(
                text = if (expanded) "Show fewer pages" else "Show all $totalPages pages",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
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
                            // @spec SEARCH-UI-006
                            text = result.displayTitle,
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
                            color = Terracotta,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .background(Terracotta.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = if (result.isFilenameMatch) {
                            androidx.compose.ui.text.AnnotatedString(result.snippet)
                        } else {
                            buildHighlightedSnippet(
                                snippet = result.snippet,
                                highlights = result.snippetHighlights,
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

        SnippetContextMenu(
            result = result,
            expanded = showMenu,
            onDismiss = { showMenu = false },
            onOpen = onClick,
        )
    }
}

@Composable
private fun SnippetContextMenu(
    result: SearchResult,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        DropdownMenuItem(
            text = { Text("Open at page ${result.pageNumber + 1}") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(18.dp)) },
            onClick = {
                onDismiss()
                onOpen()
            },
        )
        DropdownMenuItem(
            text = { Text("Copy snippet") },
            leadingIcon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp)) },
            onClick = {
                onDismiss()
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
                onDismiss()
                // @spec SEARCH-UI-006
                val text = "${result.displayTitle} · p.${result.pageNumber + 1}\n\n${result.snippet}"
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Search filters", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                TextButton(
                    onClick = { onFiltersChange(SearchFilters()) },
                    enabled = filters != SearchFilters(),
                ) {
                    Text("Reset", fontWeight = FontWeight.SemiBold)
                }
            }

            if (availableFolders.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Folder", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        availableFolders.forEach { uri ->
                            val folderId = folderIdFromTreeUri(uri)
                            val label = folderDisplayName(uri)
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

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Indexed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IndexedWithin.entries.forEach { option ->
                        FilterChip(
                            selected = filters.indexedWithin == option,
                            onClick = { onFiltersChange(filters.copy(indexedWithin = option)) },
                            label = { Text(option.displayName) },
                        )
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

        }
    }
}

private fun folderIdFromTreeUri(uri: Uri): String =
    runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrDefault(uri.toString())

private fun folderLabel(folderId: String, availableFolders: Set<Uri>): String =
    availableFolders.firstOrNull { folderIdFromTreeUri(it) == folderId }
        ?.let { folderDisplayName(it) } ?: folderId

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveFiltersRow(
    filters: SearchFilters,
    availableFolders: Set<Uri>,
    onRemoveFolder: (String) -> Unit,
    onToggleOcrOnly: () -> Unit,
    onResetSort: () -> Unit,
    onResetIndexedWithin: () -> Unit,
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

        if (filters.indexedWithin != IndexedWithin.ANY_TIME) {
            FilterChip(
                selected = true,
                onClick = onResetIndexedWithin,
                label = { Text("Indexed: ${filters.indexedWithin.displayName}") },
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
    indexedCount: Int?,
    recentSearches: List<String>,
    recentDocuments: List<DocumentEntity>,
    onSelectRecentSearch: (String) -> Unit,
    onOpenDocument: (DocumentEntity) -> Unit,
    onOpenLibrary: () -> Unit,
    customTitles: Map<String, String> = emptyMap(),
    showNoIndexPrompt: Boolean = true,
    extraContent: (LazyListScope.() -> Unit)? = null,
) {
    // Count not loaded yet — render nothing rather than flashing the
    // "Nothing indexed" prompt at a user whose library is full.
    if (indexedCount == null) {
        Box(modifier = Modifier.fillMaxSize())
        return
    }
    // Nothing indexed yet — keep the original onboarding-style prompt.
    if (indexedCount == 0 && showNoIndexPrompt) {
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
                    // @spec SEARCH-UI-003
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
                    // @spec SEARCH-UI-004, SEARCH-UI-005
                    "Recently opened",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                )
            }
            items(recentDocuments, key = { it.id }) { doc ->
                RecentDocumentRow(
                    doc = doc,
                    displayTitle = DocumentTitles.displayTitle(customTitles[doc.uri], doc.derivedTitle, doc.filename),
                    onClick = { onOpenDocument(doc) },
                )
            }
        }

        // Merged Documents screen: the library body continues the idle surface.
        // @spec NAV-006
        extraContent?.invoke(this)

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
private fun RecentDocumentRow(doc: DocumentEntity, displayTitle: String, onClick: () -> Unit) {
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
                // @spec SEARCH-UI-006
                text = displayTitle,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
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
            Text(
                // @spec LIB-PLU-001
                text = quantity(doc.pageCount, "page"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SearchErrorState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Search failed",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Something went wrong while searching the index. Edit the query to try again.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun NoResultsState(
    query: String,
    hasActiveFilters: Boolean,
    onOpenLibrary: () -> Unit,
    onClearQuery: () -> Unit,
    onClearFilters: () -> Unit,
) {
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
        if (hasActiveFilters) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Filters are active and may be hiding matches.",
                style = MaterialTheme.typography.bodyMedium,
                color = Terracotta,
            )
        }
        Spacer(Modifier.height(16.dp))
        // FlowRow, not Row: with the filter button visible all three can't fit on
        // one line, and a plain Row crushes the last button until its label
        // wraps letter-by-letter.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            if (hasActiveFilters) {
                OutlinedButton(onClick = onClearFilters) { Text("Clear filters", maxLines = 1) }
            }
            OutlinedButton(onClick = onClearQuery) { Text("Clear search", maxLines = 1) }
            Button(onClick = onOpenLibrary) { Text("Open library", maxLines = 1) }
        }
    }
}

// Styles the plain snippet text from its highlight ranges. The snippet string
// itself carries no markup — copy/share uses it verbatim (SEARCH-SNIP-002).
private fun buildHighlightedSnippet(
    snippet: String,
    highlights: List<IntRange>,
    highlightColor: Color,
    highlightTextColor: Color,
): androidx.compose.ui.text.AnnotatedString = buildAnnotatedString {
    append("“")
    var i = 0
    for (range in highlights) {
        val start = range.first.coerceIn(0, snippet.length)
        val endExclusive = (range.last + 1).coerceIn(start, snippet.length)
        if (start > i) append(snippet.substring(i, start))
        withStyle(SpanStyle(color = highlightTextColor, fontWeight = FontWeight.SemiBold, background = highlightColor)) {
            append(snippet.substring(start, endExclusive))
        }
        i = endExclusive
    }
    if (i < snippet.length) append(snippet.substring(i))
    append("”")
}
