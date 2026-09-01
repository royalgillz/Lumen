package com.lumen.app.ui.viewer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
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
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.runtime.key
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
import com.lumen.app.data.fs.ExternalAccessOffer
import com.lumen.app.data.fs.ExternalAccessPickerTargets
import com.lumen.app.data.fs.LumenPdfsFolder
import com.lumen.app.domain.usecase.KeepExternalAccessUseCase
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
    // External VIEW-intent request id (0 for in-app opens): a redelivery of
    // the SAME document changes only this value, and it must re-attempt the
    // open — the redelivery carries a fresh transient grant that can recover
    // a Failed/expired state (VIEW-EXT-010).
    deliveryId: Long = 0L,
    onBack: () -> Unit,
    // A file rename changed the document's URI: the host replaces this nav
    // entry so the route arguments carry the new URI (LIB-REN-008).
    onRenamed: (newUri: String, newFilename: String, page: Int) -> Unit = { _, _, _ -> },
    viewModel: PdfViewerViewModel = hiltViewModel(),
    libraryViewModel: com.lumen.app.ui.library.LibraryViewModel = hiltViewModel(),
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
    val resolvedTitle by viewModel.displayTitle.collectAsState()
    val scrollHorizontal by viewModel.scrollHorizontal.collectAsState()
    val matchPages by viewModel.matchPages.collectAsState()
    val occurrenceOrdinal by viewModel.occurrenceOrdinal.collectAsState()
    val occurrenceTotal by viewModel.occurrenceTotal.collectAsState()
    val activePage by viewModel.activePage.collectAsState()
    val activeRectIndexOnPage by viewModel.activeRectIndexOnPage.collectAsState()
    val pageHighlights by viewModel.pageHighlights.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val isScanningFallback by viewModel.isScanningFallback.collectAsState()
    // Make-permanent offer state for the CURRENT external document (null for
    // library documents); decided once per open by the ViewModel.
    // @spec VIEW-EXT-003
    val externalAccess by viewModel.externalAccess.collectAsState()

    // ── Viewer-only Compose state ─────────────────────────────────────────────
    // Per-document state carries the document URI as its remember key: a
    // singleTop swap into a live viewer (an external VIEW intent) reuses this
    // nav entry, and the old document's search/password/dialog state must not
    // survive into the new one. Cross-document prefs (brightness, controls)
    // stay unkeyed.
    // @spec VIEW-SESS-002
    //
    // Page currently on screen, surviving process death (the ViewModel doesn't).
    // After rotation the ViewModel remembers where the reader actually was; after
    // process death this does; only a fresh open starts at the nav-arg page.
    // @spec VIEW-SESS-001
    var savedViewedPage by rememberSaveable(uri) { mutableIntStateOf(-1) }
    // True only when the process was killed mid-session: the saveable page
    // survived while the ViewModel restarted empty.
    val restoredAfterProcessDeath = remember(uri) {
        viewModel.lastViewedPage < 0 && savedViewedPage >= 0
    }
    val initialPage = viewModel.lastViewedPage.takeIf { it >= 0 }
        ?: savedViewedPage.takeIf { it >= 0 }
        ?: pageNumber
    val displayPage = remember(uri) { mutableIntStateOf(initialPage) }
    val pageCount = remember(uri) { mutableIntStateOf(0) }
    var showPageJump by remember(uri) { mutableStateOf(false) }
    var pageJumpInput by remember(uri) { mutableStateOf("") }
    var showPasswordPrompt by remember(uri) { mutableStateOf(false) }
    var passwordInput by remember(uri) { mutableStateOf("") }
    // Saveable: losing this on rotation reopened an unlocked encrypted PDF with a
    // null password and re-prompted the reader.
    var activePdfPassword by rememberSaveable(uri) { mutableStateOf<String?>(null) }
    // Counts password submissions so resubmitting an identical (wrong) password
    // still retries the open — the password value alone can't key the open effect.
    // @spec VIEW-LOCK-001
    var passwordAttempt by rememberSaveable(uri) { mutableIntStateOf(0) }
    // When opened from global search, pre-fill the in-document search bar with the
    // keyword so the reader can immediately step between occurrences.
    var isViewerSearchActive by rememberSaveable(uri) { mutableStateOf(keyword.isNotBlank()) }
    var viewerSearchText by rememberSaveable(uri) { mutableStateOf(keyword) }
    // The query whose highlights stay on screen when the search bar is hidden.
    // Cleared on explicit dismiss (X / toggle off) — falling back to the nav-arg
    // keyword there resurrected the original search and teleported the view back
    // to its first match.
    var committedQuery by rememberSaveable(uri) { mutableStateOf(keyword) }
    // Pass the tapped occurrence to the first search only.
    var initialSearchDone by rememberSaveable(uri) { mutableStateOf(false) }
    var resumePromptShown by rememberSaveable(uri) { mutableStateOf(false) }
    // After process death the restored query re-runs only to rebuild highlights
    // and counts — its landing jump must not steal the restored reading position.
    // Fresh opens and rotation never set this flag.
    // @spec VIEW-SESS-001
    val restoredQuery = remember(uri) {
        (if (isViewerSearchActive) viewerSearchText else committedQuery).trim()
    }
    var suppressRestoredSearchJump by remember(uri) {
        mutableStateOf(restoredAfterProcessDeath && restoredQuery.length >= 2)
    }
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

    // Details sheet for the open document (library documents only). The Open
    // action is hidden — the document is already open. A successful file rename
    // swaps this screen to the new URI at the current page.
    // @spec LIB-REN-007, LIB-REN-008
    val libraryDocument by viewModel.libraryDocument.collectAsState()
    com.lumen.app.ui.library.LibraryDocumentSheetHost(
        viewModel = libraryViewModel,
        onOpenDocument = { _, _, _ -> },
        snackbarHostState = snackbarHostState,
        showOpenAction = false,
        onFileRenamed = { newUri, newFilename ->
            onRenamed(newUri, newFilename, viewModel.lastViewedPage.coerceAtLeast(0))
        },
    )

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

    // ── External-access offer plumbing ────────────────────────────────────────
    // The three escape hatches for an external document whose access would
    // otherwise die with the next VIEW intent. Pickers pre-aim via
    // EXTRA_INITIAL_URI targets built from the document id (LIB-EXT-013).

    // "Make this PDF permanent" guided sheet (Downloads root, API 30+ only).
    var showLumenPdfsSheet by remember(uri) { mutableStateOf(false) }
    // null = creating, true = ready, false = failed.
    var lumenPdfsFolderReady by remember(uri) { mutableStateOf<Boolean?>(null) }

    // ADD_FOLDER: the standard add-folder flow (persists grants + enqueues the
    // index pass), then the ViewModel promotes/clears when the grant covers
    // the current document.
    // @spec VIEW-EXT-005
    val addFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { tree ->
        if (tree != null) {
            scope.launch {
                libraryViewModel.addFolderAndWait(tree)
                viewModel.onExternalFolderAdded()
                snackbarHostState.showSnackbar("Folder added — Lumen is indexing it")
            }
        }
    }

    // Lumen-pdfs flow, step 3: same add-folder flow, picker pre-aimed at
    // Download/Lumen-pdfs; the enqueued index pass makes the move feel instant.
    // @spec VIEW-EXT-007
    val lumenPdfsTreeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { tree ->
        if (tree != null) {
            showLumenPdfsSheet = false
            scope.launch {
                libraryViewModel.addFolderAndWait(tree)
                viewModel.onExternalFolderAdded()
                snackbarHostState.showSnackbar("Lumen-pdfs added — indexing now")
            }
        }
    }

    // KEEP_FILE: single-file re-pick, pre-aimed at the containing folder when
    // one is derivable. The contract is remembered per document URI, and the
    // banner's document URI is captured at LAUNCH time — a document swap while
    // the picker is up must not misclassify the pick against the new document.
    // @spec VIEW-EXT-006
    var keepAccessOriginal by rememberSaveable(uri) { mutableStateOf<String?>(null) }
    val keepFileContract = remember(uri) {
        object : ActivityResultContracts.OpenDocument() {
            override fun createIntent(context: Context, input: Array<String>): Intent =
                super.createIntent(context, input).apply {
                    ExternalAccessPickerTargets.containingFolderInitialUri(uri)?.let { aim ->
                        putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(aim))
                    }
                }
        }
    }
    val keepFileLauncher = rememberLauncherForActivityResult(keepFileContract) { picked ->
        val original = keepAccessOriginal
        keepAccessOriginal = null
        if (picked != null && original != null) {
            viewModel.onKeepAccessPicked(original, picked)
        }
    }

    // Keep-access outcomes: confirmation, distinct failure messages, and the
    // nav-entry replacement when the pick landed on a different URI (the same
    // replace pattern a file rename uses — the old URI's stores were re-keyed).
    // @spec VIEW-EXT-006
    LaunchedEffect(Unit) {
        viewModel.keepAccessEvents.collect { event ->
            when (val result = event.result) {
                is KeepExternalAccessUseCase.Result.Kept ->
                    snackbarHostState.showSnackbar("Lumen will keep access to this PDF")
                is KeepExternalAccessUseCase.Result.Rekeyed ->
                    onRenamed(
                        result.newUri,
                        event.newDisplayName ?: filename,
                        viewModel.lastViewedPage.coerceAtLeast(0),
                    )
                is KeepExternalAccessUseCase.Result.DifferentDocument ->
                    onRenamed(result.newUri, event.newDisplayName ?: "PDF", 0)
                is KeepExternalAccessUseCase.Result.Failed ->
                    snackbarHostState.showSnackbar(result.message)
            }
        }
    }

    // Lumen-pdfs flow, step 1: ensure Download/Lumen-pdfs exists the moment the
    // sheet opens. Never moves or copies any PDF — it only creates the empty
    // destination; the user performs the move in their own file manager.
    // @spec LIB-EXT-011, VIEW-EXT-007
    LaunchedEffect(showLumenPdfsSheet) {
        if (showLumenPdfsSheet && lumenPdfsFolderReady != true) {
            lumenPdfsFolderReady = null
            lumenPdfsFolderReady = withContext(Dispatchers.IO) {
                if (Build.VERSION.SDK_INT >= 30) {
                    runCatching { LumenPdfsFolder.ensureExists(context) }.getOrDefault(false)
                } else {
                    false // flow is never offered below API 30
                }
            }
        }
    }

    // The query whose occurrences drive the overlay: the in-document search text
    // while the bar is open, otherwise the last committed query.
    val effectiveQuery = (if (isViewerSearchActive) viewerSearchText else committedQuery).trim()
    val viewerSearchQuery = viewerSearchText.trim()
    // Loaded FOR THIS URI: during a singleTop swap the old document's Loaded
    // state is still current for a frame and must not gate the new document's
    // resume/search effects open.
    val isLoaded =
        (documentState as? PdfViewerViewModel.DocumentState.Loaded)?.uri == uri
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
    // passwordAttempt keys the effect so resubmitting an identical password
    // still retries instead of silently doing nothing. deliveryId keys it so a
    // singleTop REDELIVERY of the same external document re-attempts the open
    // with the fresh grant it carries — without it, a viewer stuck on the
    // expired screen ignores the very reopen the screen asks for (the VM's
    // no-op guard keeps an already-Loaded document from reloading).
    // @spec VIEW-LOCK-001, VIEW-EXT-010
    LaunchedEffect(uri, activePdfPassword, passwordAttempt, deliveryId) {
        if (!parsedUriIsValid) return@LaunchedEffect
        viewModel.openDocument(uri, activePdfPassword, displayName = filename)
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
        // Captured during composition, before the SideEffect below paints the
        // opaque scrim color over it, so leaving the viewer restores the host
        // screens' status bar instead of leaking the scrim everywhere.
        val previousStatusBarColor = remember { activity.window.statusBarColor }
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
                // A viewer entry replaced in place (the LIB-REN-008 rename swap)
                // composes under the previous viewer's scrim, so the captured
                // color can itself be the scrim — restoring it would leak the
                // scrim onto every later screen; fall back to the edge-to-edge
                // default instead.
                window.statusBarColor =
                    if (previousStatusBarColor == statusBarColor) android.graphics.Color.TRANSPARENT
                    else previousStatusBarColor
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
        // Also compare against initialPage: restoring after process death already
        // opens at the saved page, so offering to "resume" there is noise.
        if (lastPage != pageNumber && lastPage != initialPage) {
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
    LaunchedEffect(uri, effectiveQuery, activePdfPassword, isLoaded) {
        if (!isLoaded) return@LaunchedEffect
        delay(180)
        // Any query other than the restored one is user-driven — its jumps must
        // never be swallowed, even if the restored search itself found nothing.
        // @spec VIEW-SESS-001
        if (effectiveQuery != restoredQuery) suppressRestoredSearchJump = false
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
    // (e.g. rotation), losing the reader's position. Keyed on uri so the collector
    // always holds the current document's suppression flag.
    LaunchedEffect(uri) {
        viewModel.scrollToPage.collect { page ->
            if (suppressRestoredSearchJump) {
                // The one navigation the restored search fires after process
                // death; highlights and counts still rebuild.
                // @spec VIEW-SESS-001
                suppressRestoredSearchJump = false
                return@collect
            }
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

    // Activating in-document search clears any active text selection — both
    // overlays compete for the same page real estate and the same amber/green
    // visual channel.
    // @spec VIEW-SEL-006
    LaunchedEffect(isViewerSearchActive) {
        if (isViewerSearchActive) pdfDocView.value?.clearTextSelection()
    }

    // Sync renderer → view when the document loads / changes. The uri check
    // matters during a singleTop swap: the old document's Loaded state is still
    // current for a frame and must not be wired to the new document's view.
    LaunchedEffect(documentState) {
        val v = pdfDocView.value ?: return@LaunchedEffect
        when (val state = documentState) {
            is PdfViewerViewModel.DocumentState.Loaded -> {
                if (state.uri != uri) return@LaunchedEffect
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
                    // Every tap retries, even with an identical password; the
                    // dialog stays up so a rejection surfaces its error line
                    // right here (Loaded closes it).
                    // @spec VIEW-LOCK-001
                    passwordAttempt++
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

    if (showLumenPdfsSheet) {
        LumenPdfsFlowSheet(
            filename = filename.ifBlank { "this PDF" },
            sourceFolder = remember(uri) {
                com.lumen.app.data.fs.DocumentLocations.folderDisplayPath(uri)
            },
            folderReady = lumenPdfsFolderReady,
            onShowFileLocation = {
                openFolderForDocument(context, uri) {
                    scope.launch { snackbarHostState.showSnackbar("No app can open this folder") }
                }
            },
            onAddLumenPdfs = {
                runCatching {
                    lumenPdfsTreeLauncher.launch(
                        Uri.parse(ExternalAccessPickerTargets.lumenPdfsInitialUri())
                    )
                }.onFailure {
                    scope.launch { snackbarHostState.showSnackbar("Couldn't open the folder picker") }
                }
            },
            onDismiss = { showLumenPdfsSheet = false },
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
                    // An expired external doc gets its escape hatch right on the
                    // failure screen — the same keep-file picker the banner
                    // offers, not a dead-end message.
                    // @spec VIEW-EXT-009
                    // The recovery action ships with the offer surface (v1.2:
                    // off) — the honest message stays either way.
                    // @spec LIB-EXT-021
                    val accessExpired = state.message == MSG_EXTERNAL_ACCESS_EXPIRED &&
                        com.lumen.app.domain.model.ExternalAccessFeature.OFFERS_ENABLED
                    PdfErrorScreen(
                        message = state.message,
                        onBack = onBack,
                        onRetry = when {
                            accessExpired -> {
                                {
                                    keepAccessOriginal = uri
                                    runCatching { keepFileLauncher.launch(arrayOf("application/pdf")) }
                                        .onFailure {
                                            keepAccessOriginal = null
                                            scope.launch { snackbarHostState.showSnackbar("Couldn't open the file picker") }
                                        }
                                    Unit
                                }
                            }
                            canRetry -> { { viewModel.retry() } }
                            else -> null
                        },
                        retryLabel = if (accessExpired) "Keep access to this file" else "Try again",
                    )
                } else if (isLocked) {
                    PdfErrorScreen(
                        message = "This PDF is password-protected.",
                        onBack = onBack,
                        onRetry = { showPasswordPrompt = true },
                    )
                } else {
                    // key(uri): a singleTop document swap must rebuild the view and
                    // its listener — both capture per-document state objects (and
                    // the URI reading progress is saved under), which are re-created
                    // for the new document.
                    // @spec VIEW-SESS-002
                    key(uri) {
                        AndroidView(
                            factory = { ctx ->
                                PdfDocumentView(ctx).also { v ->
                                    pdfDocView.value = v
                                    v.setListener(object : PdfDocumentView.Listener {
                                        override fun onPageChanged(currentPage: Int, totalPages: Int) {
                                            displayPage.intValue = currentPage
                                            pageCount.intValue = totalPages
                                            savedViewedPage = currentPage
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
                                    if (state is PdfViewerViewModel.DocumentState.Loaded &&
                                        state.uri == uri
                                    ) {
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

            // Make-permanent offer: one quiet, dismissible line under the top
            // chrome, only for the current external document while an offer
            // applies. Dismissal persists — the offer never nags.
            // @spec VIEW-EXT-003, VIEW-EXT-004
            val access = externalAccess
            if (access != null && access.docUri == uri &&
                access.offer != ExternalAccessOffer.NONE && isLoaded
            ) {
                ExternalAccessBanner(
                    offer = access.offer,
                    onAddFolder = {
                        runCatching {
                            addFolderLauncher.launch(
                                ExternalAccessPickerTargets.containingFolderInitialUri(uri)
                                    ?.let { Uri.parse(it) }
                            )
                        }.onFailure {
                            scope.launch { snackbarHostState.showSnackbar("Couldn't open the folder picker") }
                        }
                    },
                    onKeepFile = {
                        keepAccessOriginal = access.docUri
                        runCatching { keepFileLauncher.launch(arrayOf("application/pdf")) }
                            .onFailure {
                                keepAccessOriginal = null
                                scope.launch { snackbarHostState.showSnackbar("Couldn't open the file picker") }
                            }
                    },
                    onMakePermanent = { showLumenPdfsSheet = true },
                    onDismiss = { viewModel.dismissExternalOffer() },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
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
                                // Display title, falling back to the filename arg
                                // (which keeps serving share/print/save-copy).
                                // @spec SEARCH-UI-006
                                text = resolvedTitle ?: filename,
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
                                if (libraryDocument != null) {
                                    // Opens the shared document detail sheet
                                    // (rename lives there). External VIEW-intent
                                    // opens have no library row and no Details.
                                    // @spec LIB-REN-007
                                    DropdownMenuItem(
                                        text = { Text("Details") },
                                        leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            controlsTouchTick++
                                            libraryDocument?.let { libraryViewModel.showDocumentDetail(it) }
                                        },
                                    )
                                }
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
    retryLabel: String = "Try again",
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
                Text(retryLabel)
            }
            Spacer(Modifier.height(10.dp))
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Go back")
        }
    }
}

/**
 * The make-permanent offer: one line of honesty about transient access plus
 * the escape hatch(es) the decision matrix picked, in the same floating-card
 * idiom as the zoom pill. LUMEN_PDFS_FLOW keeps the single-file re-pick
 * offered alongside as the lighter option.
 */
// @spec VIEW-EXT-003, VIEW-EXT-007
@Composable
private fun ExternalAccessBanner(
    offer: ExternalAccessOffer,
    onAddFolder: () -> Unit,
    onKeepFile: () -> Unit,
    onMakePermanent: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 4.dp,
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Lumen may lose access to this PDF after you close it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Dismiss this offer",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (offer) {
                    ExternalAccessOffer.ADD_FOLDER ->
                        TextButton(onClick = onAddFolder) { Text("Add its folder to Lumen") }
                    ExternalAccessOffer.KEEP_FILE ->
                        TextButton(onClick = onKeepFile) { Text("Keep access to this file") }
                    ExternalAccessOffer.LUMEN_PDFS_FLOW -> {
                        TextButton(onClick = onKeepFile) { Text("Keep access") }
                        TextButton(onClick = onMakePermanent) { Text("Make permanent") }
                    }
                    ExternalAccessOffer.NONE -> Unit
                }
            }
        }
    }
}

/**
 * Guided "Make this PDF permanent" sheet (Downloads root, API 30+): the
 * Download root itself is ungrantable, so the user moves the file — with their
 * own file manager, Lumen never copies or moves anything — into a grantable
 * `Download/Lumen-pdfs` folder and adds that to the library.
 */
// @spec VIEW-EXT-007
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LumenPdfsFlowSheet(
    filename: String,
    sourceFolder: String?,
    folderReady: Boolean?,
    onShowFileLocation: () -> Unit,
    onAddLumenPdfs: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(
                "Make this PDF permanent",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Lumen never moves or copies your files. Move this PDF once and Lumen will keep it indexed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            LumenPdfsStepRow(
                number = "1",
                text = "Create the folder Download/${LumenPdfsFolder.FOLDER_NAME}",
            ) {
                Text(
                    text = when (folderReady) {
                        null -> "Creating…"
                        true -> "Ready"
                        false -> "Couldn't create it"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (folderReady == false) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            if (folderReady == false) {
                Text(
                    "You can create \"${LumenPdfsFolder.FOLDER_NAME}\" inside Download yourself in your file manager, then continue.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 32.dp, top = 2.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            LumenPdfsStepRow(
                number = "2",
                text = "Move this PDF into ${LumenPdfsFolder.FOLDER_NAME} with your file manager",
            ) {
                TextButton(onClick = onShowFileLocation) { Text("Show file location") }
            }
            // The folder jump is best-effort — some file managers open wherever
            // they please — so the name and place to look for are always spelled
            // out; the user can navigate there by hand regardless.
            // @spec VIEW-EXT-008
            Text(
                text = if (sourceFolder != null) {
                    "Look for “$filename” in $sourceFolder."
                } else {
                    "Look for “$filename”."
                },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 32.dp, top = 2.dp),
            )
            Spacer(Modifier.height(10.dp))
            LumenPdfsStepRow(
                number = "3",
                text = "Add ${LumenPdfsFolder.FOLDER_NAME} to your library",
            ) {
                TextButton(onClick = onAddLumenPdfs) { Text("Add to library") }
            }
        }
    }
}

@Composable
private fun LumenPdfsStepRow(
    number: String,
    text: String,
    trailing: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
            shape = RoundedCornerShape(percent = 50),
        ) {
            Text(
                number,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

/**
 * Best-effort jump to the document's own folder in the system Files app for
 * the guided flow's move step — the same directory-typed VIEW intent shape
 * the library's Location row uses; where nothing handles it, the step text
 * still tells the user where to go.
 */
// @spec VIEW-EXT-007
private fun openFolderForDocument(
    context: android.content.Context,
    docUri: String,
    onError: () -> Unit,
) {
    // Volume-correct: the document's own containing folder — an SD-card
    // Download file must not open internal storage's Download — falling back
    // to the primary Download directory when none is derivable.
    val folderUri = runCatching {
        Uri.parse(
            ExternalAccessPickerTargets.containingFolderInitialUri(docUri)
                ?: ExternalAccessPickerTargets.downloadsInitialUri()
        )
    }.getOrNull()
    if (folderUri == null) {
        onError()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(folderUri, DocumentsContract.Document.MIME_TYPE_DIR)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }.onFailure { onError() }
}
