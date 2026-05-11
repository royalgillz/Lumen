package com.lumen.app.ui.viewer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.lumen.app.ui.theme.AmberAccent
import kotlinx.coroutines.delay

@Composable
fun PdfViewerScreen(
    uri: String,
    pageNumber: Int,
    filename: String,
    keyword: String = "",
    onBack: () -> Unit,
    viewModel: PdfViewerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = context as? Activity
    val isDarkTheme = isSystemInDarkTheme()
    val parsedUriIsValid = remember(uri) { runCatching { Uri.parse(uri) }.getOrNull() != null }

    val documentState by viewModel.documentState.collectAsState()
    val scrollHorizontal by viewModel.scrollHorizontal.collectAsState()
    val highlights by viewModel.highlights.collectAsState()
    val highlightSkipped by viewModel.highlightSkipped.collectAsState()
    val matchPages by viewModel.viewerMatchPages.collectAsState()
    val matchIndex by viewModel.viewerMatchIndex.collectAsState()

    // ── Viewer-only Compose state ─────────────────────────────────────────────
    val displayPage = remember { mutableIntStateOf(pageNumber) }
    val pageCount = remember { mutableIntStateOf(0) }
    var showPageJump by remember { mutableStateOf(false) }
    var pageJumpInput by remember { mutableStateOf("") }
    var showPasswordPrompt by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    var activePdfPassword by remember { mutableStateOf<String?>(null) }
    var isViewerSearchActive by remember { mutableStateOf(false) }
    var viewerSearchText by remember { mutableStateOf("") }
    var showBrightnessSlider by remember { mutableStateOf(false) }
    var brightness by remember { mutableFloatStateOf(0.5f) }
    var showControls by remember { mutableStateOf(true) }
    var controlsTouchTick by remember { mutableIntStateOf(0) }

    val pdfDocView = remember { mutableStateOf<PdfDocumentView?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    val activeHighlightQuery = if (isViewerSearchActive) viewerSearchText.trim() else keyword.trim()
    val latestHighlightQuery by rememberUpdatedState(activeHighlightQuery)
    val latestPdfPassword by rememberUpdatedState(activePdfPassword)
    val viewerSearchQuery = viewerSearchText.trim()
    val viewerChromeColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f)
    val statusBarScrimColor = MaterialTheme.colorScheme.surfaceVariant
    val statusBarColor = statusBarScrimColor.toArgb()
    var topBarHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val topBarHeightDp = with(density) { topBarHeightPx.toDp() }
    val isFailed = documentState is PdfViewerViewModel.DocumentState.Failed
    val isLocked = documentState is PdfViewerViewModel.DocumentState.NeedsPassword && !showPasswordPrompt
    val topChromePadding by animateDpAsState(
        targetValue = if (showControls || isViewerSearchActive) topBarHeightDp else 0.dp,
        animationSpec = tween(180),
        label = "viewer_top_chrome_padding",
    )

    // ── Open the document on first composition / when password changes ───────
    LaunchedEffect(uri, activePdfPassword) {
        if (!parsedUriIsValid) return@LaunchedEffect
        viewModel.openDocument(uri, activePdfPassword)
    }

    // ── React to VM state transitions ─────────────────────────────────────────
    LaunchedEffect(documentState) {
        when (documentState) {
            PdfViewerViewModel.DocumentState.NeedsPassword -> {
                showPasswordPrompt = true
                showControls = true
            }
            is PdfViewerViewModel.DocumentState.Failed -> {
                showControls = true
            }
            is PdfViewerViewModel.DocumentState.Loaded -> {
                if (showPasswordPrompt) showPasswordPrompt = false
            }
            else -> Unit
        }
    }

    // Status-bar contrast — keep the icon contrast aligned with the app theme.
    if (activity != null) {
        SideEffect {
            val window = activity.window
            window.statusBarColor = statusBarColor
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDarkTheme
        }
        DisposableEffect(Unit) {
            onDispose {
                val window = activity.window
                val lp = window.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = lp
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDarkTheme
            }
        }
    }

    // Auto-hide controls after 4.2s — but never while an error or password
    // prompt is shown, since the toolbar is the only way out.
    LaunchedEffect(showControls, controlsTouchTick, isFailed, isLocked) {
        if (showControls && !isViewerSearchActive && !isFailed && !isLocked) {
            delay(4200)
            showControls = false
            showBrightnessSlider = false
        }
    }

    // Brightness override
    LaunchedEffect(brightness, showBrightnessSlider) {
        if (showBrightnessSlider) {
            val window = activity?.window
            if (window != null) {
                val lp = window.attributes
                lp.screenBrightness = brightness
                window.attributes = lp
            }
        }
    }

    // Resume-at-last-page snackbar
    LaunchedEffect(uri, documentState is PdfViewerViewModel.DocumentState.Loaded) {
        if (documentState !is PdfViewerViewModel.DocumentState.Loaded) return@LaunchedEffect
        val lastPage = viewModel.getLastPage(uri)
        if (lastPage != null && lastPage != pageNumber) {
            val result = snackbarHostState.showSnackbar(
                message = "Resume from p. ${lastPage + 1}",
                actionLabel = "Go",
            )
            if (result == SnackbarResult.ActionPerformed) {
                pdfDocView.value?.jumpToPage(lastPage, animate = true)
                displayPage.intValue = lastPage
                if (activeHighlightQuery.isNotBlank()) {
                    viewModel.loadHighlights(uri, lastPage, activeHighlightQuery, activePdfPassword)
                }
            }
        }
    }

    // Initial keyword highlight load
    LaunchedEffect(uri, pageNumber, activeHighlightQuery, activePdfPassword,
        documentState is PdfViewerViewModel.DocumentState.Loaded) {
        if (documentState !is PdfViewerViewModel.DocumentState.Loaded) return@LaunchedEffect
        viewModel.loadHighlights(uri, pageNumber, activeHighlightQuery, activePdfPassword)
    }

    // Sync highlights → view
    LaunchedEffect(highlights, displayPage.intValue) {
        val h = highlights
        val v = pdfDocView.value ?: return@LaunchedEffect
        if (h != null && h.rects.isNotEmpty() && h.pageWidthPts > 0f) {
            v.setHighlight(displayPage.intValue, h.rects, h.pageWidthPts, h.pageHeightPts)
        } else {
            v.clearHighlight()
        }
    }

    // Highlight-skipped snackbar
    LaunchedEffect(highlightSkipped) {
        if (highlightSkipped) {
            snackbarHostState.showSnackbar(
                message = "Highlights not shown — PDF exceeds 50 MB",
                duration = SnackbarDuration.Short,
            )
            viewModel.resetHighlightSkipped()
        }
    }

    // In-viewer match navigation
    LaunchedEffect(matchIndex, matchPages, activeHighlightQuery, activePdfPassword) {
        if (matchPages.isNotEmpty()) {
            val page = matchPages.getOrNull(matchIndex) ?: return@LaunchedEffect
            pdfDocView.value?.jumpToPage(page, animate = true)
            viewModel.loadHighlights(uri, page, activeHighlightQuery, activePdfPassword)
        }
    }

    // Debounced in-viewer search; one-letter input never queries
    LaunchedEffect(isViewerSearchActive, viewerSearchQuery, activePdfPassword) {
        if (!isViewerSearchActive) return@LaunchedEffect
        delay(180)
        if (viewerSearchQuery.length < 2) {
            viewModel.searchInDocument(uri, "")
            viewModel.clearHighlights()
            return@LaunchedEffect
        }
        viewModel.searchInDocument(uri, viewerSearchQuery)
        viewModel.loadHighlights(uri, displayPage.intValue, viewerSearchQuery, activePdfPassword)
    }

    // Sync scroll mode → view
    LaunchedEffect(scrollHorizontal) {
        pdfDocView.value?.setScrollHorizontal(scrollHorizontal)
    }

    // Sync renderer → view when the document loads / changes
    LaunchedEffect(documentState) {
        val v = pdfDocView.value ?: return@LaunchedEffect
        when (val state = documentState) {
            is PdfViewerViewModel.DocumentState.Loaded -> {
                v.setRenderer(state.renderer, pageNumber)
                v.setScrollHorizontal(scrollHorizontal)
                pageCount.intValue = state.renderer.pageCount
            }
            else -> Unit
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────
    if (showPasswordPrompt) {
        AlertDialog(
            onDismissRequest = {
                showPasswordPrompt = false
                passwordInput = ""
            },
            title = { Text("Encrypted PDF") },
            text = {
                TextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it },
                    label = { Text("Enter PDF password") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    activePdfPassword = passwordInput.ifBlank { null }
                    showPasswordPrompt = false
                }) { Text("Open") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPasswordPrompt = false
                    passwordInput = ""
                }) { Text("Cancel") }
            },
        )
    }

    if (showPageJump && pageCount.intValue > 0) {
        AlertDialog(
            onDismissRequest = { showPageJump = false },
            title = { Text("Go to page") },
            text = {
                TextField(
                    value = pageJumpInput,
                    onValueChange = { pageJumpInput = it.filter { c -> c.isDigit() } },
                    label = { Text("Page (1–${pageCount.intValue})") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = pageJumpInput.toIntOrNull()?.minus(1)
                    if (target != null && target in 0 until pageCount.intValue) {
                        pdfDocView.value?.jumpToPage(target, animate = true)
                        if (activeHighlightQuery.isNotBlank()) {
                            viewModel.loadHighlights(uri, target, activeHighlightQuery, activePdfPassword)
                        }
                    }
                    showPageJump = false
                    pageJumpInput = ""
                }) { Text("Go") }
            },
            dismissButton = {
                TextButton(onClick = { showPageJump = false; pageJumpInput = "" }) { Text("Cancel") }
            },
        )
    }

    // ── Main layout ───────────────────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = topChromePadding),
        ) {
            if (!parsedUriIsValid) {
                PdfErrorScreen(
                    message = "Unable to open this PDF — the link is invalid.",
                    onBack = onBack,
                )
            } else {
                val state = documentState
                if (state is PdfViewerViewModel.DocumentState.Failed) {
                    val canRetry = state.message.contains("too much memory", ignoreCase = true)
                    PdfErrorScreen(
                        message = state.message,
                        onBack = onBack,
                        onRetry = if (canRetry) { { viewModel.retry() } } else null,
                    )
                } else if (isLocked) {
                    PdfErrorScreen(
                        message = "This PDF is password-protected.",
                        onBack = onBack,
                        onRetry = { showPasswordPrompt = true },
                    )
                } else {
                    AndroidView(
                        factory = { ctx ->
                            PdfDocumentView(ctx).also { v ->
                                pdfDocView.value = v
                                v.setListener(object : PdfDocumentView.Listener {
                                    override fun onPageChanged(currentPage: Int, totalPages: Int) {
                                        displayPage.intValue = currentPage
                                        pageCount.intValue = totalPages
                                        viewModel.saveLastPage(uri, currentPage)
                                        if (latestHighlightQuery.isNotBlank()) {
                                            viewModel.loadHighlights(
                                                uri, currentPage, latestHighlightQuery, latestPdfPassword,
                                            )
                                        }
                                    }
                                    override fun onSingleTap() {
                                        showControls = !showControls
                                        if (!showControls) {
                                            showBrightnessSlider = false
                                            isViewerSearchActive = false
                                        }
                                        controlsTouchTick++
                                    }
                                    override fun onExternalLinkTap(uri: String) {
                                        handleExternalLink(ctx, uri) {
                                            showControls = true
                                            controlsTouchTick++
                                        }
                                    }
                                    override fun onInternalLinkTap(pageIndex: Int) {
                                        v.jumpToPage(pageIndex, animate = true)
                                        if (latestHighlightQuery.isNotBlank()) {
                                            viewModel.loadHighlights(
                                                uri, pageIndex, latestHighlightQuery, latestPdfPassword,
                                            )
                                        }
                                    }
                                    override fun onZoomChanged(zoom: Float) { /* no-op */ }
                                })
                                if (state is PdfViewerViewModel.DocumentState.Loaded) {
                                    v.setRenderer(state.renderer, pageNumber)
                                    v.setScrollHorizontal(scrollHorizontal)
                                    pageCount.intValue = state.renderer.pageCount
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            if (!isFailed && !isLocked && parsedUriIsValid) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ZoomButton(icon = { Icon(Icons.Default.Add, contentDescription = "Zoom in") }) {
                        pdfDocView.value?.zoomBy(1.25f)
                        showControls = true
                        controlsTouchTick++
                    }
                    ZoomButton(icon = { Icon(Icons.Default.Remove, contentDescription = "Zoom out") }) {
                        pdfDocView.value?.zoomBy(1f / 1.25f)
                        showControls = true
                        controlsTouchTick++
                    }
                }
            }

            if (documentState is PdfViewerViewModel.DocumentState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 90.dp),
            )
        }

        // Status-bar scrim — keep the status-bar zone opaque so the PDF doesn't
        // bleed/blur into it on edge-to-edge displays.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .background(statusBarScrimColor),
        )

        AnimatedVisibility(
            visible = showControls || isViewerSearchActive,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { -it / 2 },
            exit = fadeOut(tween(180)) + slideOutVertically(tween(180)) { -it / 2 },
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { topBarHeightPx = it.height }
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                color = viewerChromeColor,
                shadowElevation = 4.dp,
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = {
                            showControls = true
                            controlsTouchTick++
                            onBack()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = filename,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (pageCount.intValue > 0) {
                                Text(
                                    text = "p. ${displayPage.intValue + 1} of ${pageCount.intValue}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.clickable {
                                        showPageJump = true
                                        controlsTouchTick++
                                    },
                                )
                            }
                        }
                        IconButton(onClick = {
                            isViewerSearchActive = !isViewerSearchActive
                            if (!isViewerSearchActive) {
                                viewerSearchText = ""
                                viewModel.searchInDocument(uri, "")
                            }
                            showControls = true
                            controlsTouchTick++
                        }) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = "Search in document",
                                tint = if (isViewerSearchActive) AmberAccent else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        IconButton(onClick = { showPageJump = true; controlsTouchTick++ }) {
                            Icon(Icons.Default.Pin, contentDescription = "Go to page")
                        }
                        IconButton(onClick = {
                            showBrightnessSlider = !showBrightnessSlider
                            controlsTouchTick++
                        }) {
                            Icon(
                                Icons.Default.BrightnessMedium,
                                contentDescription = "Brightness",
                                tint = if (showBrightnessSlider) AmberAccent else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        IconButton(onClick = { viewModel.toggleScrollMode(); controlsTouchTick++ }) {
                            Icon(
                                imageVector = if (scrollHorizontal) Icons.Default.SwapVert else Icons.Default.SwapHoriz,
                                contentDescription = null,
                            )
                        }
                    }
                    AnimatedVisibility(
                        visible = isViewerSearchActive,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextField(
                                value = viewerSearchText,
                                onValueChange = {
                                    viewerSearchText = it
                                    controlsTouchTick++
                                },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("Search in document…", style = MaterialTheme.typography.bodySmall) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                ),
                                textStyle = MaterialTheme.typography.bodyMedium,
                            )
                            if (matchPages.isNotEmpty()) {
                                Text(
                                    text = "${matchIndex + 1} / ${matchPages.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 4.dp),
                                )
                                IconButton(
                                    onClick = { viewModel.prevMatch() },
                                    enabled = matchPages.size > 1,
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        Icons.Default.KeyboardArrowUp,
                                        contentDescription = "Previous match",
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.nextMatch() },
                                    enabled = matchPages.size > 1,
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Next match",
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            } else if (viewerSearchQuery.length in 1..1) {
                                Text(
                                    "Type at least 2 letters",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                )
                            } else if (viewerSearchText.isNotBlank()) {
                                Text(
                                    "No matches",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                )
                            }
                            IconButton(
                                onClick = {
                                    isViewerSearchActive = false
                                    viewerSearchText = ""
                                    viewModel.searchInDocument(uri, "")
                                    controlsTouchTick++
                                },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close search", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    AnimatedVisibility(
                        visible = showBrightnessSlider,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Default.BrightnessLow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Slider(
                                value = brightness,
                                onValueChange = {
                                    brightness = it
                                    controlsTouchTick++
                                },
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(thumbColor = AmberAccent, activeTrackColor = AmberAccent),
                            )
                            Icon(
                                Icons.Default.BrightnessHigh,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "${(brightness * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(36.dp),
                                textAlign = TextAlign.End,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun handleExternalLink(
    context: android.content.Context,
    rawUri: String,
    onError: () -> Unit,
) {
    if (rawUri.isBlank()) return
    val normalized = if (rawUri.startsWith("http://", ignoreCase = true) ||
        rawUri.startsWith("https://", ignoreCase = true) ||
        rawUri.startsWith("mailto:", ignoreCase = true) ||
        rawUri.startsWith("tel:", ignoreCase = true)
    ) rawUri else "https://$rawUri"
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(normalized)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    } catch (_: ActivityNotFoundException) {
        onError()
    } catch (_: SecurityException) {
        onError()
    }
}

@Composable
private fun PdfErrorScreen(
    message: String,
    onBack: () -> Unit,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        if (onRetry != null) {
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text("Try again")
            }
            Spacer(Modifier.height(10.dp))
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Go back")
        }
    }
}

@Composable
private fun ZoomButton(icon: @Composable () -> Unit, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
        shadowElevation = 4.dp,
        modifier = Modifier.size(44.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { icon() }
    }
}
