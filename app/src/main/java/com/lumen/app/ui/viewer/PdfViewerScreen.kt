package com.lumen.app.ui.viewer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.onSizeChanged
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.lumen.app.ui.theme.AmberAccent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.FileNotFoundException

private const val ZOOM_STEP = 1.25f
private const val ZOOM_MIN = 1f
private const val ZOOM_MID = 3.25f
private const val ZOOM_MAX = 8f

private data class PanOffset(val x: Float, val y: Float)

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
    val parsedUri = remember(uri) { runCatching { Uri.parse(uri) }.getOrNull() }

    // ── Persistent state (survives scroll-mode rebuild) ───────────────────────
    val displayPage = remember { mutableIntStateOf(pageNumber) }
    val pageCount by viewModel.pageCount.collectAsState()
    var isLoading by remember { mutableStateOf(parsedUri != null) }
    var showPageJump by remember { mutableStateOf(false) }
    var pageJumpInput by remember { mutableStateOf("") }
    var showPasswordPrompt by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    var activePdfPassword by remember { mutableStateOf<String?>(null) }
    var pdfReloadToken by remember { mutableIntStateOf(0) }
    var runtimeOpenError by remember { mutableStateOf<String?>(null) }

    // In-viewer search
    var isViewerSearchActive by remember { mutableStateOf(false) }
    var viewerSearchText by remember { mutableStateOf("") }

    // Brightness / overflow menu
    var showBrightnessSlider by remember { mutableStateOf(false) }
    var brightness by remember { mutableFloatStateOf(0.5f) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var controlsTouchTick by remember { mutableIntStateOf(0) }

    // Snackbar for reading-progress resume
    val snackbarHostState = remember { SnackbarHostState() }

    val scrollHorizontal by viewModel.scrollHorizontal.collectAsState()
    LaunchedEffect(showControls, controlsTouchTick, runtimeOpenError) {
        // Don't auto-hide while an error is shown — the toolbar is the only way back
        if (showControls && !isViewerSearchActive && !showBrightnessSlider && !showOverflowMenu && runtimeOpenError == null) {
            delay(4200)
            showControls = false
            showBrightnessSlider = false
        }
    }

    val highlights by viewModel.highlights.collectAsState()
    val highlightSkipped by viewModel.highlightSkipped.collectAsState()
    val matchPages by viewModel.viewerMatchPages.collectAsState()
    val matchIndex by viewModel.viewerMatchIndex.collectAsState()
    val activeHighlightQuery = if (isViewerSearchActive) viewerSearchText.trim() else keyword.trim()
    val latestHighlightQuery by rememberUpdatedState(activeHighlightQuery)
    val latestPdfPassword by rememberUpdatedState(activePdfPassword)
    val viewerSearchQuery = viewerSearchText.trim()
    val viewerChromeColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f)
    val statusBarScrimColor = MaterialTheme.colorScheme.surfaceVariant
    val statusBarColor = statusBarScrimColor.toArgb()
    var topBarHeightPx by remember { mutableIntStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val topBarHeightDp = with(density) { topBarHeightPx.toDp() }
    val topChromePadding by animateDpAsState(
        targetValue = if (showControls || isViewerSearchActive) topBarHeightDp else 0.dp,
        animationSpec = tween(180),
        label = "viewer_top_chrome_padding",
    )
    val pagerState = rememberPagerState(initialPage = pageNumber) { if (pageCount > 0) pageCount else 1 }
    var viewportSizePx by remember { mutableStateOf(IntSize.Zero) }
    var currentScale by remember { mutableFloatStateOf(1f) }
    var currentOffsetX by remember { mutableFloatStateOf(0f) }
    var currentOffsetY by remember { mutableFloatStateOf(0f) }
    var pendingJumpPage by remember { mutableStateOf<Int?>(null) }
    var zoomInClicks by remember { mutableIntStateOf(0) }
    var zoomOutClicks by remember { mutableIntStateOf(0) }
    var pagerScrollEnabled by remember { mutableStateOf(true) }
    var scaleAppliedToPage by remember { mutableIntStateOf(pageNumber) }

    // Keep status bar icon contrast aligned with the app theme.
    if (activity != null) {
        SideEffect {
            val window = activity.window
            @Suppress("DEPRECATION")
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

    // ── Brightness effects ────────────────────────────────────────────────────
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

    // ── Reading progress: show resume snackbar on open ────────────────────────
    LaunchedEffect(uri) {
        val lastPage = viewModel.getLastPage(uri)
        if (lastPage != null && lastPage != pageNumber) {
            val result = snackbarHostState.showSnackbar(
                message = "Resume from p. ${lastPage + 1}",
                actionLabel = "Go",
            )
            if (result == SnackbarResult.ActionPerformed) {
                pendingJumpPage = lastPage
            }
        }
    }

    // ── Initial keyword highlights ────────────────────────────────────────────
    LaunchedEffect(uri, pageNumber, activeHighlightQuery, activePdfPassword) {
        viewModel.loadHighlights(uri, pageNumber, activeHighlightQuery, activePdfPassword)
    }

    // ── Highlight-skipped snackbar ────────────────────────────────────────────
    LaunchedEffect(highlightSkipped) {
        if (highlightSkipped) {
            snackbarHostState.showSnackbar(
                message = "Highlights not shown — PDF exceeds 25 MB",
                duration = SnackbarDuration.Short,
            )
            viewModel.resetHighlightSkipped()
        }
    }

    // ── In-viewer match navigation ────────────────────────────────────────────
    LaunchedEffect(matchIndex, matchPages, activeHighlightQuery, activePdfPassword) {
        if (matchPages.isNotEmpty()) {
            val page = matchPages.getOrNull(matchIndex) ?: return@LaunchedEffect
            pendingJumpPage = page
            viewModel.loadHighlights(uri, page, activeHighlightQuery, activePdfPassword)
        }
    }
    LaunchedEffect(pagerState.currentPage) {
        scaleAppliedToPage = pagerState.currentPage
        displayPage.intValue = pagerState.currentPage
        viewModel.saveLastPage(uri, pagerState.currentPage)
        if (activeHighlightQuery.isNotBlank()) {
            viewModel.loadHighlights(uri, pagerState.currentPage, activeHighlightQuery, activePdfPassword)
        }
        currentScale = 1f
        currentOffsetX = 0f
        currentOffsetY = 0f
        pagerScrollEnabled = true
        isLoading = false
    }
    LaunchedEffect(pendingJumpPage, pageCount) {
        val target = pendingJumpPage ?: return@LaunchedEffect
        if (target !in 0 until (if (pageCount > 0) pageCount else 1)) return@LaunchedEffect
        pagerState.animateScrollToPage(target)
        pendingJumpPage = null
    }

    // Debounced in-viewer search. Prevents noisy one-letter highlights while typing.
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

    // ── Page jump dialog ──────────────────────────────────────────────────────
    if (showPasswordPrompt) {
        AlertDialog(
            onDismissRequest = { showPasswordPrompt = false },
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
                    pdfReloadToken++
                    runtimeOpenError = null
                    showPasswordPrompt = false
                    isLoading = true
                }) { Text("Open") }
            },
            dismissButton = {
                TextButton(onClick = { showPasswordPrompt = false }) { Text("Cancel") }
            },
        )
    }

    // ── Page jump dialog ──────────────────────────────────────────────────────
    if (showPageJump && pageCount > 0) {
        AlertDialog(
            onDismissRequest = { showPageJump = false },
            title = { Text("Go to page") },
            text = {
                TextField(
                    value = pageJumpInput,
                    onValueChange = { pageJumpInput = it.filter { c -> c.isDigit() } },
                    label = { Text("Page (1–${pageCount})") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = pageJumpInput.toIntOrNull()?.minus(1)
                    if (target != null && target in 0 until pageCount) {
                        pendingJumpPage = target
                        if (activeHighlightQuery.isNotBlank()) viewModel.loadHighlights(uri, target, activeHighlightQuery, activePdfPassword)
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = topChromePadding)
        ) {
            if (parsedUri != null) {
                key(scrollHorizontal, activePdfPassword.orEmpty(), pdfReloadToken) {
                    val openErrorMsg = remember(parsedUri, pdfReloadToken) {
                        try {
                            context.contentResolver.openFileDescriptor(parsedUri, "r")?.use {}
                            null
                        } catch (_: SecurityException) {
                            "Permission denied.\nGo to Library, remove this folder, and re-add it to restore access."
                        } catch (_: FileNotFoundException) {
                            "File not found.\nThis PDF may have been moved or deleted."
                        } catch (_: Exception) {
                            "Unable to open this PDF."
                        }
                    }

                    LaunchedEffect(openErrorMsg) {
                        if (openErrorMsg != null) {
                            runtimeOpenError = openErrorMsg
                            isLoading = false
                            showControls = true
                        }
                    }

                    if (openErrorMsg == null && runtimeOpenError == null) {
                        val pager: @Composable () -> Unit = {
                            BoxWithConstraints(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .onSizeChanged { viewportSizePx = it }
                            ) {
                                val widthPx = constraints.maxWidth.coerceAtLeast(1)
                                val pageContent: @Composable (Int) -> Unit = { pageIndex ->
                                    val bitmap by produceState<android.graphics.Bitmap?>(null, pageIndex, widthPx, pageCount, activePdfPassword, pdfReloadToken) {
                                        value = try {
                                            viewModel.renderPage(uri, pageIndex, widthPx, activePdfPassword)
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (throwable: Throwable) {
                                            if (isLikelyEncryptedPdf(throwable.message.orEmpty())) {
                                                showPasswordPrompt = true
                                            } else {
                                                runtimeOpenError = userMessageForPdfLoadFailure(throwable)
                                            }
                                            null
                                        }
                                        if (pageIndex == pagerState.currentPage) {
                                            isLoading = false
                                        }
                                    }
                                    val pageSize by produceState<android.graphics.PointF?>(null, pageIndex, activePdfPassword) {
                                        value = viewModel.getPageSize(uri, pageIndex, activePdfPassword)
                                    }
                                    val pageLinks by produceState<List<MuLink>>(emptyList(), pageIndex, activePdfPassword) {
                                        value = viewModel.getLinks(uri, pageIndex, activePdfPassword)
                                    }
                                    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
                                        if (pageIndex != pagerState.currentPage) return@rememberTransformableState
                                        currentScale = (currentScale * zoomChange).coerceIn(ZOOM_MIN, ZOOM_MAX)
                                        val panSpeed = 1.5f
                                        val clamped = clampPanOffset(
                                            viewportWidth = viewportSizePx.width.toFloat(),
                                            viewportHeight = viewportSizePx.height.toFloat(),
                                            bitmap = bitmap,
                                            scale = currentScale,
                                            offsetX = currentOffsetX + panChange.x * panSpeed,
                                            offsetY = currentOffsetY + panChange.y * panSpeed,
                                        )
                                        currentOffsetX = clamped.x
                                        currentOffsetY = clamped.y
                                        pagerScrollEnabled = currentScale <= 1.02f
                                    }
                                    LaunchedEffect(zoomInClicks, pageIndex) {
                                        if (pageIndex == pagerState.currentPage && zoomInClicks > 0) {
                                            currentScale = (currentScale * ZOOM_STEP).coerceAtMost(ZOOM_MAX)
                                            val clamped = clampPanOffset(
                                                viewportWidth = viewportSizePx.width.toFloat(),
                                                viewportHeight = viewportSizePx.height.toFloat(),
                                                bitmap = bitmap,
                                                scale = currentScale,
                                                offsetX = currentOffsetX,
                                                offsetY = currentOffsetY,
                                            )
                                            currentOffsetX = clamped.x
                                            currentOffsetY = clamped.y
                                            pagerScrollEnabled = currentScale <= 1.02f
                                        }
                                    }
                                    LaunchedEffect(zoomOutClicks, pageIndex) {
                                        if (pageIndex == pagerState.currentPage && zoomOutClicks > 0) {
                                            currentScale = (currentScale / ZOOM_STEP).coerceAtLeast(ZOOM_MIN)
                                            val clamped = clampPanOffset(
                                                viewportWidth = viewportSizePx.width.toFloat(),
                                                viewportHeight = viewportSizePx.height.toFloat(),
                                                bitmap = bitmap,
                                                scale = currentScale,
                                                offsetX = currentOffsetX,
                                                offsetY = currentOffsetY,
                                            )
                                            currentOffsetX = clamped.x
                                            currentOffsetY = clamped.y
                                            pagerScrollEnabled = currentScale <= 1.02f
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clipToBounds()
                                            .pointerInput(pageLinks, pageSize, pageIndex) {
                                                detectTapGestures(
                                                    onTap = { tap ->
                                                        val sizePts = pageSize ?: return@detectTapGestures
                                                        val rendered = renderedPageRect(
                                                            viewportWidth = viewportSizePx.width.toFloat(),
                                                            viewportHeight = viewportSizePx.height.toFloat(),
                                                            bitmap = bitmap,
                                                            scale = if (pageIndex == pagerState.currentPage) currentScale else 1f,
                                                            offsetX = if (pageIndex == pagerState.currentPage) currentOffsetX else 0f,
                                                            offsetY = if (pageIndex == pagerState.currentPage) currentOffsetY else 0f,
                                                        ) ?: return@detectTapGestures
                                                        if (!rendered.contains(tap)) return@detectTapGestures
                                                        val xPts = ((tap.x - rendered.left) / rendered.width) * sizePts.x
                                                        val yPts = ((tap.y - rendered.top) / rendered.height) * sizePts.y
                                                        val tapped = pageLinks.firstOrNull { it.bounds.contains(xPts, yPts) }
                                                        if (tapped != null) {
                                                            when {
                                                                tapped.pageTarget != null -> pendingJumpPage = tapped.pageTarget
                                                                !tapped.uri.isNullOrBlank() -> {
                                                                    val targetUri = tapped.uri
                                                                    val normalized = if (targetUri.startsWith("http://", true) || targetUri.startsWith("https://", true)) targetUri else "https://$targetUri"
                                                                    try {
                                                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(normalized)).apply {
                                                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                                        })
                                                                    } catch (_: ActivityNotFoundException) {
                                                                        showControls = true
                                                                        controlsTouchTick++
                                                                    }
                                                                }
                                                            }
                                                        } else {
                                                            showControls = !showControls
                                                            if (!showControls) {
                                                                showBrightnessSlider = false
                                                                isViewerSearchActive = false
                                                            }
                                                            controlsTouchTick++
                                                        }
                                                    }
                                                )
                                            },
                                    ) {
                                        if (bitmap != null) {
                                            androidx.compose.foundation.Image(
                                                bitmap = bitmap!!.asImageBitmap(),
                                                contentDescription = "PDF page ${pageIndex + 1}",
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .graphicsLayer {
                                                        val applyZoom = pageIndex == pagerState.currentPage && scaleAppliedToPage == pagerState.currentPage
                                                        scaleX = if (applyZoom) currentScale else 1f
                                                        scaleY = if (applyZoom) currentScale else 1f
                                                        translationX = if (applyZoom) currentOffsetX else 0f
                                                        translationY = if (applyZoom) currentOffsetY else 0f
                                                    }
                                                    .transformable(
                                                        state = transformableState,
                                                        canPan = {
                                                            pageIndex == pagerState.currentPage && currentScale > 1.02f
                                                        },
                                                    ),
                                            )
                                            val pageHighlights = highlights
                                            if (pageHighlights != null && pageHighlights.pageWidthPts > 0f && pageIndex == displayPage.intValue) {
                                                androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                                                    val rendered = renderedPageRect(
                                                        viewportWidth = size.width,
                                                        viewportHeight = size.height,
                                                        bitmap = bitmap,
                                                        scale = if (pageIndex == pagerState.currentPage) currentScale else 1f,
                                                        offsetX = if (pageIndex == pagerState.currentPage) currentOffsetX else 0f,
                                                        offsetY = if (pageIndex == pagerState.currentPage) currentOffsetY else 0f,
                                                    ) ?: return@Canvas
                                                    pageHighlights.rects.forEach { rect ->
                                                        val scaleX = rendered.width / pageHighlights.pageWidthPts
                                                        val scaleY = rendered.height / pageHighlights.pageHeightPts
                                                        drawRect(
                                                            color = AmberAccent.copy(alpha = 0.35f),
                                                            topLeft = Offset(
                                                                rendered.left + rect.left * scaleX,
                                                                rendered.top + rect.top * scaleY
                                                            ),
                                                            size = Size(
                                                                (rect.right - rect.left) * scaleX,
                                                                (rect.bottom - rect.top) * scaleY
                                                            )
                                                        )
                                                    }
                                                }
                                            }
                                        } else {
                                            CircularProgressIndicator(Modifier.align(Alignment.Center))
                                        }
                                    }
                                }
                                if (scrollHorizontal) {
                                    HorizontalPager(state = pagerState, userScrollEnabled = pagerScrollEnabled, modifier = Modifier.fillMaxSize(), key = { it }, pageSpacing = 2.dp) { pageIndex ->
                                        pageContent(pageIndex)
                                    }
                                } else {
                                    VerticalPager(state = pagerState, userScrollEnabled = pagerScrollEnabled, modifier = Modifier.fillMaxSize(), key = { it }, pageSpacing = 2.dp) { pageIndex ->
                                        pageContent(pageIndex)
                                    }
                                }
                                if (pageCount > 1) {
                                    val scrollPos = pagerState.currentPage.toFloat() / (pageCount - 1).toFloat()
                                    val thumbFraction = (1f / pageCount).coerceAtLeast(0.04f)
                                    val spaceBefore = (scrollPos * (1f - thumbFraction)).coerceIn(0f, 1f - thumbFraction)
                                    val spaceAfter = (1f - thumbFraction - spaceBefore).coerceAtLeast(0f)
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.CenterEnd)
                                            .padding(end = 2.dp)
                                            .width(3.dp)
                                            .fillMaxHeight(0.7f),
                                    ) {
                                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f), RoundedCornerShape(2.dp)))
                                        Column(Modifier.fillMaxSize()) {
                                            if (spaceBefore > 0.001f) Spacer(Modifier.weight(spaceBefore))
                                            Box(Modifier.fillMaxWidth().weight(thumbFraction).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f), RoundedCornerShape(2.dp)))
                                            if (spaceAfter > 0.001f) Spacer(Modifier.weight(spaceAfter))
                                        }
                                    }
                                }
                            }
                        }
                        pager()
                    } else {
                        val errorText = runtimeOpenError ?: openErrorMsg ?: "Unable to open this PDF."
                        val canRetry = errorText.contains("too much memory", ignoreCase = true)
                        PdfErrorScreen(
                            message = errorText,
                            onBack = onBack,
                            onRetry = if (canRetry) {
                                {
                                    runtimeOpenError = null
                                    pdfReloadToken++
                                    isLoading = true
                                }
                            } else null,
                        )
                    }
                }
            } else {
                PdfErrorScreen(
                    message = "Unable to open this PDF — the link is invalid.",
                    onBack = onBack,
                )
            }

            if (runtimeOpenError == null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ZoomButton(icon = { Icon(Icons.Default.Add, contentDescription = "Zoom in") }) {
                        zoomInClicks++
                        showControls = true
                        controlsTouchTick++
                    }
                    ZoomButton(icon = { Icon(Icons.Default.Remove, contentDescription = "Zoom out") }) {
                        zoomOutClicks++
                        showControls = true
                        controlsTouchTick++
                    }
                }
            }

            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.primary)
            }

            SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp))
        }

        // Always keep the status-bar zone opaque so PDF content doesn't bleed/blur under it.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .background(statusBarScrimColor)
        )

        AnimatedVisibility(
            visible = showControls || isViewerSearchActive,
            modifier = Modifier
                .align(Alignment.TopCenter),
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
                            if (pageCount > 0) {
                                Text(
                                    text = "p. ${displayPage.intValue + 1} of ${pageCount}",
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
                            Icon(Icons.Default.Search, contentDescription = "Search in document", tint = if (isViewerSearchActive) AmberAccent else MaterialTheme.colorScheme.onSurface)
                        }
                        Box {
                            IconButton(onClick = { showOverflowMenu = true; controlsTouchTick++ }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Go to page") },
                                    leadingIcon = { Icon(Icons.Default.Pin, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                    onClick = { showOverflowMenu = false; showPageJump = true; controlsTouchTick++ },
                                )
                                DropdownMenuItem(
                                    text = { Text("Brightness") },
                                    leadingIcon = { Icon(Icons.Default.BrightnessMedium, contentDescription = null, modifier = Modifier.size(20.dp), tint = if (showBrightnessSlider) AmberAccent else MaterialTheme.colorScheme.onSurface) },
                                    onClick = { showOverflowMenu = false; showBrightnessSlider = !showBrightnessSlider; controlsTouchTick++ },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (scrollHorizontal) "Vertical scroll" else "Horizontal scroll") },
                                    leadingIcon = { Icon(if (scrollHorizontal) Icons.Default.SwapVert else Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                    onClick = { showOverflowMenu = false; viewModel.toggleScrollMode(); controlsTouchTick++ },
                                )
                            }
                        }
                    }
                    AnimatedVisibility(visible = isViewerSearchActive, enter = expandVertically(), exit = shrinkVertically()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                TextField(
                    value = viewerSearchText,
                    onValueChange = { text ->
                        viewerSearchText = text
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
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous match", modifier = Modifier.size(20.dp))
                    }
                    IconButton(
                        onClick = { viewModel.nextMatch() },
                        enabled = matchPages.size > 1,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next match", modifier = Modifier.size(20.dp))
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
                    AnimatedVisibility(visible = showBrightnessSlider, enter = expandVertically(), exit = shrinkVertically()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Default.BrightnessLow, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Slider(
                                value = brightness,
                                onValueChange = {
                                    brightness = it
                                    controlsTouchTick++
                                },
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(thumbColor = AmberAccent, activeTrackColor = AmberAccent),
                            )
                            Icon(Icons.Default.BrightnessHigh, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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

private fun isLikelyEncryptedPdf(message: String): Boolean =
    message.contains("password", ignoreCase = true) || message.contains("encrypted", ignoreCase = true)

private fun renderedPageRect(
    viewportWidth: Float,
    viewportHeight: Float,
    bitmap: android.graphics.Bitmap?,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
): Rect? {
    if (bitmap == null || viewportWidth <= 0f || viewportHeight <= 0f) return null
    val fitScale = minOf(
        viewportWidth / bitmap.width.toFloat(),
        viewportHeight / bitmap.height.toFloat(),
    )
    val drawWidth = bitmap.width * fitScale * scale
    val drawHeight = bitmap.height * fitScale * scale
    val left = (viewportWidth - drawWidth) / 2f + offsetX
    val top = (viewportHeight - drawHeight) / 2f + offsetY
    return Rect(left, top, left + drawWidth, top + drawHeight)
}

private fun clampPanOffset(
    viewportWidth: Float,
    viewportHeight: Float,
    bitmap: android.graphics.Bitmap?,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
): PanOffset {
    val rect = renderedPageRect(viewportWidth, viewportHeight, bitmap, scale, 0f, 0f)
        ?: return PanOffset(0f, 0f)
    val maxX = ((rect.width - viewportWidth) / 2f).coerceAtLeast(0f)
    val maxY = ((rect.height - viewportHeight) / 2f).coerceAtLeast(0f)
    return PanOffset(
        x = offsetX.coerceIn(-maxX, maxX),
        y = offsetY.coerceIn(-maxY, maxY),
    )
}

private fun userMessageForPdfLoadFailure(throwable: Throwable): String {
    if (throwable is OutOfMemoryError) {
        return "This PDF requires too much memory to display. Close other apps and try again, or view it on a PC if it contains many high-resolution scanned pages."
    }
    val message = throwable.message.orEmpty()
    if (message.contains("Failed to allocate", ignoreCase = true) ||
        message.contains("OutOfMemory", ignoreCase = true) ||
        message.contains("OOM", ignoreCase = true)) {
        return "This PDF requires too much memory to display. Close other apps and try again, or view it on a PC if it contains many high-resolution scanned pages."
    }
    return if (message.isNotBlank()) message else "Unable to open this PDF."
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
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Try again")
            }
            Spacer(Modifier.height(10.dp))
        }
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Go back")
        }
    }
}

@Composable
private fun ZoomButton(
    icon: @Composable () -> Unit,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.92f else 0.45f),
        shadowElevation = if (enabled) 4.dp else 0.dp,
        modifier = Modifier.size(44.dp),
        enabled = enabled,
    ) {
        Box(contentAlignment = Alignment.Center) { icon() }
    }
}
