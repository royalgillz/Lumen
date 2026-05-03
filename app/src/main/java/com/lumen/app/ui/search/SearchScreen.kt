package com.lumen.app.ui.search

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lumen.app.domain.model.SearchResult
import com.lumen.app.ui.theme.AmberAccent

@Composable
fun SearchScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    onResultClick: (uri: String, page: Int, filename: String, keyword: String) -> Unit = { _, _, _, _ -> },
    onOpenLibrary: () -> Unit = {},
) {
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val isTruncated by viewModel.isTruncated.collectAsState()
    val indexedCount by viewModel.indexedCount.collectAsState()
    val isIndexing by viewModel.isIndexing.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        SearchHeader(
            query = query,
            indexedCount = indexedCount,
            onQueryChange = { viewModel.query.value = it },
            onClearQuery = { viewModel.query.value = "" },
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
                Text(
                    "Indexing\u2026",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                query.trim().length < 2 -> SearchEmptyState(
                    indexedCount = indexedCount,
                    onOpenLibrary = onOpenLibrary,
                )
                isSearching -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary,
                )
                results.isEmpty() -> NoResultsState(
                    query = query,
                    onOpenLibrary = onOpenLibrary,
                    onClearQuery = { viewModel.query.value = "" },
                )
                else -> ResultList(
                    query = query,
                    results = results,
                    isTruncated = isTruncated,
                    onResultClick = { uri, page, filename ->
                        onResultClick(uri, page, filename, query.trim())
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
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

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
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Shield,
                    contentDescription = "Privacy: offline only",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .focusRequester(focusRequester),
            placeholder = { Text("Search your PDFs\u2026") },
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = if (query.isNotEmpty()) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = onClearQuery) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = "Clear search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
        )

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

        HorizontalDivider()
    }
}

@Composable
private fun ResultList(
    query: String,
    results: List<SearchResult>,
    isTruncated: Boolean,
    onResultClick: (uri: String, page: Int, filename: String) -> Unit,
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
        items(results, key = { it.lineId }) { result ->
            ResultRow(
                query = query,
                result = result,
                onClick = { onResultClick(result.uri, result.pageNumber, result.filename) },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (isTruncated) {
            item {
                Text(
                    text = "Showing first 200 results — try a more specific query",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Lumen snippet", result.snippet)
                    clipboard.setPrimaryClip(clip)
                    // Android 13+ shows its own clipboard toast; show one for older versions
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(context, "Snippet copied", Toast.LENGTH_SHORT).show()
                    }
                },
            )
            .padding(horizontal = 16.dp, vertical = 13.dp)
    ) {
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
                text = "p. ${result.pageNumber + 1}",
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
            text = buildHighlightedSnippet(
                query = query,
                snippet = result.snippet,
                highlightColor = AmberAccent.copy(alpha = 0.18f),
                highlightTextColor = MaterialTheme.colorScheme.onBackground,
            ),
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

@Composable
private fun SearchEmptyState(
    indexedCount: Int,
    onOpenLibrary: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "What are you looking for?",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (indexedCount > 0)
                "Search across $indexedCount indexed ${if (indexedCount == 1) "PDF" else "PDFs"}"
            else
                "Add a folder in the library to start searching",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onOpenLibrary) {
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text("Go to library")
        }
    }
}

@Composable
private fun NoResultsState(
    query: String,
    onOpenLibrary: () -> Unit,
    onClearQuery: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "No results for \u201C$query\u201D",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Try a different word, or check that the folder is indexed",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onClearQuery) {
                Text("Clear search")
            }
            Button(onClick = onOpenLibrary) {
                Text("Open library")
            }
        }
    }
}

private fun buildHighlightedSnippet(
    query: String,
    snippet: String,
    highlightColor: Color,
    highlightTextColor: Color,
) = buildAnnotatedString {
    append("\u201C\u2026")
    if (query.isBlank()) {
        append(snippet)
        append("\u2026\u201D")
        return@buildAnnotatedString
    }

    val tokens = query
        .trim()
        .split(Regex("\\s+"))
        .filter { it.length >= 2 }
        .distinct()

    if (tokens.isEmpty()) {
        append(snippet)
        append("\u2026\u201D")
        return@buildAnnotatedString
    }

    val ranges = mutableListOf<IntRange>()
    val lowerSnippet = snippet.lowercase()
    for (token in tokens) {
        val lowerToken = token.lowercase()
        var searchStart = 0
        while (searchStart < lowerSnippet.length) {
            val found = lowerSnippet.indexOf(lowerToken, searchStart)
            if (found == -1) break
            ranges += IntRange(found, found + lowerToken.length - 1)
            searchStart = found + lowerToken.length
        }
    }

    if (ranges.isEmpty()) {
        append(snippet)
        append("\u2026\u201D")
        return@buildAnnotatedString
    }

    val merged = ranges.sortedBy { it.first }.fold(mutableListOf<IntRange>()) { acc, current ->
        if (acc.isEmpty()) {
            acc += current
        } else {
            val previous = acc.last()
            if (current.first <= previous.last + 1) {
                acc[acc.lastIndex] = IntRange(previous.first, maxOf(previous.last, current.last))
            } else {
                acc += current
            }
        }
        acc
    }

    var cursor = 0
    for (range in merged) {
        if (cursor < range.first) append(snippet.substring(cursor, range.first))
        withStyle(
            SpanStyle(
                color = highlightTextColor,
                fontWeight = FontWeight.SemiBold,
                background = highlightColor,
            )
        ) {
            append(snippet.substring(range.first, range.last + 1))
        }
        cursor = range.last + 1
    }
    if (cursor < snippet.length) append(snippet.substring(cursor))
    append("\u2026\u201D")
}
