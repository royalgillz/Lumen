package com.lumen.app.ui.viewer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.lumen.app.data.db.entity.BookmarkEntity
import com.lumen.app.ui.theme.LocalLumenDarkTheme
import com.lumen.app.ui.theme.Terracotta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    uri: String,
    pageNumber: Int,
    filename: String,
    keyword: String = "",
    occurrence: Int = 0,
    onBack: () -> Unit,
    viewModel: PdfViewerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = context as? Activity
    // The resolved Theme setting, not the system theme: the viewer's status-bar
    // contrast and canvas colors must follow the user's choice. Only the chrome
    // and the canvas AROUND pages are themed — page content renders exactly as
    // the document specifies, never inverted.
    // @spec SET-APPEAR-003, SET-APPEAR-004
    val isDarkTheme = LocalLumenDarkTheme.current
    val parsedUriIsValid = remember(uri) { runCatching { Uri.parse(uri) }.getOrNull() != null }

    val documentState by viewModel.documentState.collectAsState()
    val scrollHorizontal by viewModel.scrollHorizontal.collectAsState()
    val matchPages by viewModel.matchPages.collectAsState()
    val occurrenceOrdinal by viewModel.occurrenceOrdinal.collectAsState()
    val occurrenceTotal by viewModel.occurrenceTotal.collectAsState()
    val activePage by viewModel.activePage.collectAsState()
    val activeRectIndexOnPage by viewModel.activeRectIndexOnPage.collectAsState()
    val pageHighlights by viewModel.pageHighlights.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val isScanningFallback by viewModel.isScanningFallback.collectAsState()

    // ── Viewer-only Compose state ─────────────────────────────────────────────
    // After rotation the ViewModel remembers where the reader actually was; only a
    // fresh open should start at the nav-arg page.
    val initialPage = viewModel.lastViewedPage.takeIf { it >= 0 } ?: pageNumber
    val displayPage = remember { mutableIntStateOf(initialPage) }
    val pageCount = remember { mutableIntStateOf(0) }
    var showPageJump by remember { mutableStateOf(false) }
    var pageJumpInput by remember { mutableStateOf("") }
    var showPasswordPrompt by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    // Saveable: losing this on rotation reopened an unlocked encrypted PDF with a
    // null password and re-prompted the reader.
    var activePdfPassword by rememberSaveable { mutableStateOf<String?>(null) }
    // When opened from global search, pre-fill the in-document search bar with the
    // keyword so the reader can immediately step between occurrences.
    var isViewerSearchActive by rememberSaveable { mutableStateOf(keyword.isNotBlank()) }
    var viewerSearchText by rememberSaveable { mutableStateOf(keyword) }
    // The query whose highlights stay on screen when the search bar is hidden.
    // Cleared on explicit dismiss (X / toggle off) — falling back to the nav-arg
    // keyword there resurrected the original search and teleported the view back
    // to its first match.
    var committedQuery by rememberSaveable { mutableStateOf(keyword) }
    // Pass the tapped occurrence to the first search only.
    var initialSearchDone by rememberSaveable { mutableStateOf(false) }
    var resumePromptShown by rememberSaveable { mutableStateOf(false) }
    var showBrightnessSlider by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showBookmarksSheet by remember { mutableStateOf(false) }
    var noteDialogBookmarkId by remember { mutableStateOf<Long?>(null) }
    var noteDialogText by remember { mutableStateOf("") }
    // Seed from the system brightness so opening the slider doesn't jump the
    // screen to an arbitrary 50%; the override only applies once the user drags.
    val systemBrightness = remember {
        runCatching {
            android.provider.Settings.System.getInt(
                context.contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS,
            ) / 255f
        }.getOrDefault(0.5f).coerceIn(0f, 1f)
    }
    var brightness by remember { mutableFloatStateOf(systemBrightness) }
    var brightnessTouched by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var controlsTouchTick by remember { mutableIntStateOf(0) }
    var zoomPercent by remember { mutableIntStateOf(100) }
    // True while the fast-scroll thumb is dragged or a fling runs — the zoom
    // pill yields to the thumb (they share the right edge).
    var scrollActivity by remember { mutableStateOf(false) }

    // Auto-hiding chrome is unreachable under TalkBack: the canvas-tap toggle is
    // unannounced and a hidden pill leaves the semantics tree. Keep chrome up.
    // @spec VIEW-PILL-003
    var touchExplorationEnabled by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as android.view.accessibility.AccessibilityManager
        touchExplorationEnabled = am.isTouchExplorationEnabled
        val listener = android.view.accessibility.AccessibilityManager
            .TouchExplorationStateChangeListener { touchExplorationEnabled = it }
        am.addTouchExplorationStateChangeListener(listener)
        onDispose { am.removeTouchExplorationStateChangeListener(listener) }
    }

    val pdfDocView = remember { mutableStateOf<PdfDocumentView?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // "Save a copy" — the reader picks a destination via the system file picker;
    // the PDF bytes are streamed there directly, never through app storage.
    val saveCopyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { dest ->
        if (dest != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        val src = Uri.parse(uri)
                        context.contentResolver.openInputStream(src)!!.use { input ->
                            context.contentResolver.openOutputStream(dest)!!.use { output ->
                                input.copyTo(output)
                            }
                        }
                    }.isSuccess
                }
                snackbarHostState.showSnackbar(if (saved) "Copy saved" else "Couldn't save copy")
            }
        }
    }

    // The query whose occurrences drive the overlay: the in-document search text
    // while the bar is open, otherwise the last committed query.
    val effectiveQuery = (if (isViewerSearchActive) viewerSearchText else committedQuery).trim()
    val viewerSearchQuery = viewerSearchText.trim()
    val isLoaded = documentState is PdfViewerViewModel.DocumentState.Loaded
    val viewerChromeColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f)
    val statusBarScrimColor = MaterialTheme.colorScheme.surfaceVariant
    val canvasBackground = MaterialTheme.colorScheme.surfaceVariant.toArgb()
    val canvasDivider = MaterialTheme.colorScheme.outlineVariant.toArgb()
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

    // System Back closes the in-document search before leaving the screen.
    BackHandler(enabled = isViewerSearchActive) {
        isViewerSearchActive = false
        viewerSearchText = ""
        committedQuery = ""
    }

    // ── Open the document on first composition / when password changes ───────
    LaunchedEffect(uri, activePdfPassword) {
        if (!parsedUriIsValid) return@LaunchedEffect
        viewModel.openDocument(uri, activePdfPassword)
    }

    // ── React to VM state transitions ─────────────────────────────────────────
    LaunchedEffect(documentState) {
        when (documentState) {
            is PdfViewerViewModel.DocumentState.NeedsPassword -> {
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
    // prompt is shown (the toolbar is the only way out), and never under
    // touch exploration (hidden chrome is unreachable to a screen reader).
    // @spec VIEW-PILL-001, VIEW-PILL-003
    LaunchedEffect(showControls, controlsTouchTick, isFailed, isLocked, showOverflowMenu, touchExplorationEnabled) {
        if (showControls && !isViewerSearchActive && !isFailed && !isLocked &&
            !showOverflowMenu && !touchExplorationEnabled
        ) {
            delay(4200)
            showControls = false
            showBrightnessSlider = false
        }
    }

    // Brightness override — only after the user has actually dragged the slider.
    LaunchedEffect(brightness, brightnessTouched) {
        if (brightnessTouched) {
            val window = activity?.window
            if (window != null) {
                val lp = window.attributes
                lp.screenBrightness = brightness
                window.attributes = lp
            }
        }
    }

    // Resume-at-last-page snackbar. The saved page is captured by the ViewModel
    // before this session writes any progress, so this cannot race the first save.
    LaunchedEffect(uri, isLoaded) {
        if (!isLoaded || resumePromptShown) return@LaunchedEffect
        val lastPage = viewModel.resumePage?.takeIf { it >= 0 } ?: return@LaunchedEffect
        if (lastPage != pageNumber) {
            resumePromptShown = true
            val result = snackbarHostState.showSnackbar(
                message = "Resume from p. ${lastPage + 1}",
                actionLabel = "Go",
            )
            if (result == SnackbarResult.ActionPerformed) {
                pdfDocView.value?.jumpToPage(lastPage, animate = true)
                displayPage.intValue = lastPage
            }
        }
    }

    // Debounced occurrence enumeration. The first run (the keyword the viewer was
    // opened with) carries the tapped page + occurrence so it opens on the exact
    // match; later runs (typing in the search bar) start at the first match.
    LaunchedEffect(effectiveQuery, activePdfPassword, isLoaded) {
        if (!isLoaded) return@LaunchedEffect
        delay(180)
        if (effectiveQuery.length < 2) {
            viewModel.clearSearch()
            return@LaunchedEffect
        }
        if (!initialSearchDone && keyword.isNotBlank() && effectiveQuery == keyword.trim()) {
            initialSearchDone = true
            viewModel.runInDocumentSearch(uri, effectiveQuery, activePdfPassword, pageNumber, occurrence)
        } else {
            viewModel.runInDocumentSearch(uri, effectiveQuery, activePdfPassword)
        }
    }

    // Bookmark add/remove feedback. Added offers a jump into the optional note.
    LaunchedEffect(Unit) {
        viewModel.bookmarkEvents.collect { event ->
            when (event) {
                is PdfViewerViewModel.BookmarkEvent.Added -> {
                    val result = snackbarHostState.showSnackbar(
                        message = "Bookmarked p. ${event.page + 1}",
                        actionLabel = "Add note",
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        noteDialogText = ""
                        noteDialogBookmarkId = event.id
                    }
                }
                is PdfViewerViewModel.BookmarkEvent.Removed -> {
                    snackbarHostState.showSnackbar(
                        message = "Bookmark removed",
                        duration = SnackbarDuration.Short,
                    )
                }
            }
        }
    }

    // Bring a match's page into view on explicit search actions only. Event-based:
    // keying on activePage state re-jumped the view on every recomposition-restart
    // (e.g. rotation), losing the reader's position.
    LaunchedEffect(Unit) {
        viewModel.scrollToPage.collect { page ->
            pdfDocView.value?.jumpToPage(page, animate = true)
        }
    }

    // Lazily compute highlight rects for the on-screen page and its neighbours (a
    // no-op for non-match and already-computed pages). Covers search jumps and
    // manual scrolling onto a match page; the background count pass fills the rest.
    LaunchedEffect(displayPage.intValue, matchPages) {
        viewModel.ensurePageHighlights(displayPage.intValue)
        viewModel.ensurePageHighlights(displayPage.intValue - 1)
        viewModel.ensurePageHighlights(displayPage.intValue + 1)
    }

    // Push every computed page's highlights to the view — visible neighbours paint
    // too, not just the centre page.
    LaunchedEffect(pageHighlights, activePage, activeRectIndexOnPage) {
        val v = pdfDocView.value ?: return@LaunchedEffect
        val byPage = pageHighlights
            .filterValues { it.rects.isNotEmpty() && it.pageWidthPts > 0f }
            .mapValues { (_, ph) ->
                PdfDocumentView.PageHighlightSet(ph.rects, ph.pageWidthPts, ph.pageHeightPts)
            }
        if (byPage.isEmpty()) {
            v.clearHighlight()
        } else {
            v.setHighlights(byPage, activePage, activeRectIndexOnPage)
        }
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
                v.setRenderer(state.renderer, initialPage)
                v.wordProvider = { page -> viewModel.wordsForPage(page) }
                v.setScrollHorizontal(scrollHorizontal)
                pageCount.intValue = state.renderer.pageCount
            }
            else -> Unit
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────
    if (showPasswordPrompt) {
        val wrongPassword =
            (documentState as? PdfViewerViewModel.DocumentState.NeedsPassword)?.wrongPassword == true
        AlertDialog(
            onDismissRequest = {
                showPasswordPrompt = false
                passwordInput = ""
            },
            title = { Text("Encrypted PDF") },
            text = {
                Column {
                    TextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("Enter PDF password") },
                        singleLine = true,
                        isError = wrongPassword,
                    )
                    if (wrongPassword) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Incorrect password. Try again.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
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

    val editingBookmarkId = noteDialogBookmarkId
    if (editingBookmarkId != null) {
        AlertDialog(
            onDismissRequest = { noteDialogBookmarkId = null },
            title = { Text("Bookmark note") },
            text = {
                TextField(
                    value = noteDialogText,
                    onValueChange = { noteDialogText = it },
                    label = { Text("Note (optional)") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setBookmarkNote(editingBookmarkId, noteDialogText)
                    noteDialogBookmarkId = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { noteDialogBookmarkId = null }) { Text("Cancel") }
            },
        )
    }

    if (showBookmarksSheet) {
        BookmarksSheet(
            bookmarks = bookmarks,
            onJump = { page ->
                showBookmarksSheet = false
                pdfDocView.value?.jumpToPage(page, animate = true)
            },
            onDelete = { viewModel.deleteBookmark(it) },
            onEditNote = { bm ->
                noteDialogText = bm.note.orEmpty()
                noteDialogBookmarkId = bm.id
            },
            onDismiss = { showBookmarksSheet = false },
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
                                        viewModel.noteCurrentPage(uri, currentPage)
                                    }
                                    override fun onSingleTap() {
                                        showControls = !showControls
                                        if (!showControls) {
                                            showBrightnessSlider = false
                                            // Hiding chrome keeps the current query's
                                            // highlights alive — only X/toggle dismisses.
                                            if (isViewerSearchActive) {
                                                committedQuery = viewerSearchText
                                                isViewerSearchActive = false
                                            }
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
                                    }
                                    // Zoom activity counts as interaction: show the
                                    // controls (with the pill's readout) and restart
                                    // the auto-hide timer.
                                    // @spec VIEW-PILL-002
                                    override fun onZoomChanged(zoom: Float) {
                                        zoomPercent = (zoom * 100).roundToInt().coerceAtLeast(1)
                                        showControls = true
                                        controlsTouchTick++
                                    }
                                    override fun onScrollActivityChanged(active: Boolean) {
                                        scrollActivity = active
                                    }
                                })
                                v.setCanvasColors(canvasBackground, canvasDivider)
                                if (state is PdfViewerViewModel.DocumentState.Loaded) {
                                    v.setRenderer(state.renderer, initialPage)
                                    v.wordProvider = { page -> viewModel.wordsForPage(page) }
                                    v.setScrollHorizontal(scrollHorizontal)
                                    pageCount.intValue = state.renderer.pageCount
                                }
                            }
                        },
                        update = { v -> v.setCanvasColors(canvasBackground, canvasDivider) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            if (!isFailed && !isLocked && parsedUriIsValid) {
                // Zoom pill: − · % · +. Tapping the percentage snaps back to 100%.
                // One chrome lifecycle: the pill shows and fades with the top-bar
                // controls, and yields to the fast-scroll thumb mid-scroll.
                // @spec VIEW-PILL-001, VIEW-PILL-004
                AnimatedVisibility(
                    visible = (showControls || isViewerSearchActive) && !scrollActivity,
                    modifier = Modifier.align(Alignment.BottomEnd),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                Surface(
                    modifier = Modifier.padding(16.dp),
                    shape = RoundedCornerShape(percent = 50),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shadowElevation = 4.dp,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    ) {
                        IconButton(
                            onClick = {
                                pdfDocView.value?.zoomBy(1f / 1.25f)
                                showControls = true
                                controlsTouchTick++
                            },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                Icons.Default.Remove,
                                contentDescription = "Zoom out",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            text = "$zoomPercent%",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .clickable {
                                    pdfDocView.value?.resetZoom()
                                    showControls = true
                                    controlsTouchTick++
                                }
                                .width(48.dp)
                                .padding(vertical = 10.dp),
                        )
                        IconButton(
                            onClick = {
                                pdfDocView.value?.zoomBy(1.25f)
                                showControls = true
                                controlsTouchTick++
                            },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Zoom in",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
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
                        val currentPageBookmarked = bookmarks.any { it.pageNumber == displayPage.intValue }
                        IconButton(onClick = {
                            viewModel.toggleBookmark(displayPage.intValue)
                            showControls = true
                            controlsTouchTick++
                        }) {
                            Icon(
                                imageVector = if (currentPageBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                contentDescription = if (currentPageBookmarked) "Remove bookmark" else "Bookmark this page",
                                tint = if (currentPageBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        IconButton(onClick = {
                            isViewerSearchActive = !isViewerSearchActive
                            if (!isViewerSearchActive) {
                                viewerSearchText = ""
                                committedQuery = ""
                            }
                            showControls = true
                            controlsTouchTick++
                        }) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = "Search in document",
                                tint = if (isViewerSearchActive) Terracotta else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Box {
                            IconButton(onClick = {
                                showOverflowMenu = true
                                controlsTouchTick++
                            }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Share") },
                                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        controlsTouchTick++
                                        sharePdf(context, uri, filename) {
                                            showControls = true
                                            controlsTouchTick++
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Print") },
                                    leadingIcon = { Icon(Icons.Default.Print, contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        controlsTouchTick++
                                        printPdf(context, uri, filename) {
                                            scope.launch { snackbarHostState.showSnackbar("Couldn't print this PDF") }
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Save a copy") },
                                    leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        controlsTouchTick++
                                        runCatching { saveCopyLauncher.launch(filename) }
                                            .onFailure {
                                                scope.launch { snackbarHostState.showSnackbar("Couldn't open the file picker") }
                                            }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Go to page") },
                                    leadingIcon = { Icon(Icons.Default.Pin, contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        showPageJump = true
                                        controlsTouchTick++
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Bookmarks") },
                                    leadingIcon = { Icon(Icons.Default.Bookmarks, contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        showBookmarksSheet = true
                                        controlsTouchTick++
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Brightness") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.BrightnessMedium,
                                            contentDescription = null,
                                            tint = if (showBrightnessSlider) Terracotta else MaterialTheme.colorScheme.onSurface,
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        showBrightnessSlider = !showBrightnessSlider
                                        controlsTouchTick++
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (scrollHorizontal) "Vertical scroll" else "Horizontal scroll") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (scrollHorizontal) Icons.Default.SwapVert else Icons.Default.SwapHoriz,
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        viewModel.toggleScrollMode()
                                        controlsTouchTick++
                                    },
                                )
                            }
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
                                    text = "${occurrenceOrdinal.coerceAtLeast(1)} / ${occurrenceTotal.coerceAtLeast(1)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 4.dp),
                                )
                                val canNavigate = occurrenceTotal > 1
                                IconButton(
                                    onClick = { viewModel.prevOccurrence() },
                                    enabled = canNavigate,
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        Icons.Default.KeyboardArrowUp,
                                        contentDescription = "Previous match",
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.nextOccurrence() },
                                    enabled = canNavigate,
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
                            } else if (isScanningFallback) {
                                Text(
                                    "Searching…",
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
                                    committedQuery = ""
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
                                    brightnessTouched = true
                                    controlsTouchTick++
                                },
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(thumbColor = Terracotta, activeTrackColor = Terracotta),
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

private fun sharePdf(
    context: android.content.Context,
    rawUri: String,
    filename: String,
    onError: () -> Unit,
) {
    val uri = runCatching { Uri.parse(rawUri) }.getOrNull()
    if (uri == null) {
        onError()
        return
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, filename)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(
            Intent.createChooser(send, "Share PDF").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
    } catch (_: ActivityNotFoundException) {
        onError()
    } catch (_: SecurityException) {
        onError()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun BookmarksSheet(
    bookmarks: List<BookmarkEntity>,
    onJump: (Int) -> Unit,
    onDelete: (Long) -> Unit,
    onEditNote: (BookmarkEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 28.dp)) {
            Text(
                "Bookmarks",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            if (bookmarks.isEmpty()) {
                Text(
                    "No bookmarks yet. Tap the bookmark icon in the top bar to mark the current page.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            } else {
                val dateFormat = remember {
                    java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
                }
                LazyColumn {
                    items(bookmarks, key = { it.id }) { bm ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { onJump(bm.pageNumber) },
                                    onLongClick = { onEditNote(bm) },
                                )
                                .padding(horizontal = 20.dp, vertical = 8.dp),
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
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = bm.note?.takeIf { it.isNotBlank() } ?: "No note — long-press to add",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (bm.note.isNullOrBlank()) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = dateFormat.format(java.util.Date(bm.createdAt)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { onDelete(bm.id) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete bookmark on page ${bm.pageNumber + 1}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Print via the system print framework. The PDF is already print-ready, so the
 * adapter streams the original bytes straight to the print spooler — no
 * re-rendering, and everything stays on-device.
 */
private fun printPdf(
    context: android.content.Context,
    rawUri: String,
    filename: String,
    onError: () -> Unit,
) {
    val uri = runCatching { Uri.parse(rawUri) }.getOrNull()
    if (uri == null) {
        onError()
        return
    }
    val printManager = context.getSystemService(android.content.Context.PRINT_SERVICE)
        as? android.print.PrintManager
    if (printManager == null) {
        onError()
        return
    }
    val adapter = object : android.print.PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: android.print.PrintAttributes?,
            newAttributes: android.print.PrintAttributes?,
            cancellationSignal: android.os.CancellationSignal?,
            callback: LayoutResultCallback,
            extras: android.os.Bundle?,
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            val info = android.print.PrintDocumentInfo.Builder(filename)
                .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .build()
            callback.onLayoutFinished(info, newAttributes != oldAttributes)
        }

        override fun onWrite(
            pages: Array<out android.print.PageRange>?,
            destination: android.os.ParcelFileDescriptor,
            cancellationSignal: android.os.CancellationSignal?,
            callback: WriteResultCallback,
        ) {
            try {
                val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(destination.fileDescriptor).use { output ->
                        input.copyTo(output)
                    }
                }
                if (copied == null) {
                    callback.onWriteFailed("Cannot open PDF")
                    return
                }
                callback.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(e.message)
            }
        }
    }
    try {
        printManager.print(filename, adapter, android.print.PrintAttributes.Builder().build())
    } catch (_: Exception) {
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
