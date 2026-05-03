package com.lumen.app.ui.viewer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.view.View
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
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LightMode
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.listener.OnPageChangeListener
import com.github.barteksc.pdfviewer.link.DefaultLinkHandler
import com.github.barteksc.pdfviewer.model.LinkTapEvent
import com.lumen.app.ui.theme.AmberAccent
import kotlinx.coroutines.delay
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicReference

private val NIGHT_MODE_MATRIX = ColorMatrix(
    floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    )
)

private const val ZOOM_STEP = 1.25f
private const val ZOOM_MIN = 1f
private const val ZOOM_MAX = 8f

private data class HighlightDrawData(
    val pageIndex: Int,
    val rects: List<android.graphics.RectF>,
    val pageWidthPts: Float,
    val pageHeightPts: Float,
    val paint: android.graphics.Paint,
)

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
    var pdfView by remember { mutableStateOf<PDFView?>(null) }
    val displayPage = remember { mutableIntStateOf(pageNumber) }
    val pageCount = remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(parsedUri != null) }
    var isNightMode by remember { mutableStateOf(false) }
    var showPageJump by remember { mutableStateOf(false) }
    var pageJumpInput by remember { mutableStateOf("") }
    var showPasswordPrompt by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    var activePdfPassword by remember { mutableStateOf<String?>(null) }
    var pdfReloadToken by remember { mutableIntStateOf(0) }
    var runtimeOpenError by remember { mutableStateOf<String?>(null) }

    // ── Highlight draw data shared with PDFView's onDrawAll callback ──────────
    val highlightDrawRef = remember { AtomicReference<HighlightDrawData?>(null) }
    val dayHighlightPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.argb(89, 0xD4, 0xA2, 0x4C)
            style = android.graphics.Paint.Style.FILL
        }
    }
    val nightHighlightPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.argb(140, 0xD4, 0xA2, 0x4C)
            style = android.graphics.Paint.Style.FILL
        }
    }

    // In-viewer search
    var isViewerSearchActive by remember { mutableStateOf(false) }
    var viewerSearchText by remember { mutableStateOf("") }

    // Brightness
    var showBrightnessSlider by remember { mutableStateOf(false) }
    var brightness by remember { mutableFloatStateOf(0.5f) }
    var showControls by remember { mutableStateOf(true) }
    var controlsTouchTick by remember { mutableIntStateOf(0) }

    // Snackbar for reading-progress resume
    val snackbarHostState = remember { SnackbarHostState() }

    val scrollHorizontal by viewModel.scrollHorizontal.collectAsState()
    LaunchedEffect(showControls, controlsTouchTick) {
        if (showControls && !isViewerSearchActive) {
            delay(4200)
            showControls = false
            showBrightnessSlider = false
        }
    }

    val highlights by viewModel.highlights.collectAsState()
    val matchPages by viewModel.viewerMatchPages.collectAsState()
    val matchIndex by viewModel.viewerMatchIndex.collectAsState()
    val activeHighlightQuery = if (isViewerSearchActive) viewerSearchText.trim() else keyword.trim()
    val latestHighlightQuery by rememberUpdatedState(activeHighlightQuery)
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

    // Keep status bar icon contrast aligned with the app theme.
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
                pdfView?.jumpTo(lastPage, true)
                displayPage.intValue = lastPage
                if (activeHighlightQuery.isNotBlank()) viewModel.loadHighlights(uri, lastPage, activeHighlightQuery)
            }
        }
    }

    // ── Initial keyword highlights ────────────────────────────────────────────
    LaunchedEffect(uri, pageNumber, activeHighlightQuery) {
        viewModel.loadHighlights(uri, pageNumber, activeHighlightQuery)
    }

    // ── Sync highlights → onDrawAll ref (runs on main thread, safe to invalidate) ──
    LaunchedEffect(highlights, displayPage.intValue, isNightMode) {
        val h = highlights
        highlightDrawRef.set(
            if (h != null && h.rects.isNotEmpty() && h.pageWidthPts > 0f)
                HighlightDrawData(
                    pageIndex = displayPage.intValue,
                    rects = h.rects,
                    pageWidthPts = h.pageWidthPts,
                    pageHeightPts = h.pageHeightPts,
                    paint = if (isNightMode) nightHighlightPaint else dayHighlightPaint,
                )
            else null
        )
        pdfView?.invalidate()
    }

    // ── In-viewer match navigation ────────────────────────────────────────────
    LaunchedEffect(matchIndex, matchPages) {
        if (matchPages.isNotEmpty()) {
            val page = matchPages.getOrNull(matchIndex) ?: return@LaunchedEffect
            pdfView?.jumpTo(page, true)
            viewModel.loadHighlights(uri, page, activeHighlightQuery)
        }
    }

    // Debounced in-viewer search. Prevents noisy one-letter highlights while typing.
    LaunchedEffect(isViewerSearchActive, viewerSearchQuery) {
        if (!isViewerSearchActive) return@LaunchedEffect
        delay(180)
        if (viewerSearchQuery.length < 2) {
            viewModel.searchInDocument(uri, "")
            viewModel.clearHighlights()
            return@LaunchedEffect
        }
        viewModel.searchInDocument(uri, viewerSearchQuery)
        viewModel.loadHighlights(uri, displayPage.intValue, viewerSearchQuery)
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
                        pdfView?.jumpTo(target, true)
                        if (activeHighlightQuery.isNotBlank()) viewModel.loadHighlights(uri, target, activeHighlightQuery)
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
                // key() forces full rebuild of PDFView when scroll mode changes
                key(scrollHorizontal, activePdfPassword.orEmpty(), pdfReloadToken) {
                    val (stream, openErrorMsg) = remember(parsedUri, pdfReloadToken) {
                        try {
                            Pair(context.contentResolver.openInputStream(parsedUri), null)
                        } catch (_: SecurityException) {
                            Pair(null, "Permission denied.\nGo to Library, remove this folder, and re-add it to restore access.")
                        } catch (_: FileNotFoundException) {
                            Pair(null, "File not found.\nThis PDF may have been moved or deleted.")
                        } catch (_: Exception) {
                            Pair(null, "Unable to open this PDF.")
                        }
                    }

                    DisposableEffect(stream) { onDispose { stream?.close() } }

                    if (stream != null && runtimeOpenError == null) {
                        AndroidView(
                            factory = { ctx ->
                                PDFView(ctx, null).also { v ->
                                    pdfView = v
                                    isLoading = true
                                    runCatching {
                                        val config = v.fromStream(stream)
                                            .defaultPage(displayPage.intValue)
                                            .swipeHorizontal(scrollHorizontal)
                                        if (!activePdfPassword.isNullOrBlank()) {
                                            config.password(activePdfPassword)
                                        }
                                        config
                                            .linkHandler { event ->
                                                handlePdfLinkTap(
                                                    event = event,
                                                    context = ctx,
                                                    pdfView = v,
                                                    onExternalLinkError = {
                                                        showControls = true
                                                        controlsTouchTick++
                                                    },
                                                )
                                            }
                                            .onTap {
                                                showControls = !showControls
                                                if (!showControls) {
                                                    showBrightnessSlider = false
                                                    isViewerSearchActive = false
                                                }
                                                controlsTouchTick++
                                                true
                                            }
                                            .onPageChange(object : OnPageChangeListener {
                                                override fun onPageChanged(page: Int, count: Int) {
                                                    displayPage.intValue = page
                                                    pageCount.intValue = count
                                                    viewModel.saveLastPage(uri, page)
                                                    if (latestHighlightQuery.isNotBlank()) {
                                                        viewModel.loadHighlights(uri, page, latestHighlightQuery)
                                                    }
                                                }
                                            })
                                            .onLoad { _ ->
                                                isLoading = false
                                                runtimeOpenError = null
                                                if (showPasswordPrompt) showPasswordPrompt = false
                                            }
                                            .onError { throwable ->
                                                isLoading = false
                                                val message = throwable.message.orEmpty()
                                                val encrypted = message.contains("password", ignoreCase = true) ||
                                                    message.contains("encrypted", ignoreCase = true)
                                                if (encrypted) {
                                                    showPasswordPrompt = true
                                                } else {
                                                    runtimeOpenError = if (message.isNotBlank()) message else "Unable to open this PDF."
                                                }
                                            }
                                            .enableSwipe(true)
                                            .enableDoubletap(true)
                                            .enableAnnotationRendering(true)
                                            .onDrawAll { canvas, pageWidth, pageHeight, displayedPage ->
                                                val data = highlightDrawRef.get() ?: return@onDrawAll
                                                if (data.pageIndex != displayedPage || data.pageWidthPts <= 0f) return@onDrawAll
                                                val scaleX = pageWidth / data.pageWidthPts
                                                val scaleY = pageHeight / data.pageHeightPts
                                                for (rect in data.rects) {
                                                    canvas.drawRect(
                                                        rect.left * scaleX, rect.top * scaleY,
                                                        rect.right * scaleX, rect.bottom * scaleY,
                                                        data.paint,
                                                    )
                                                }
                                            }
                                            .load()
                                    }.onFailure { throwable ->
                                        isLoading = false
                                        val message = throwable.message.orEmpty()
                                        val encrypted = message.contains("password", ignoreCase = true) ||
                                            message.contains("encrypted", ignoreCase = true)
                                        if (encrypted) {
                                            showPasswordPrompt = true
                                        } else {
                                            runtimeOpenError = if (message.isNotBlank()) message else "Unable to open this PDF."
                                        }
                                    }
                                }
                            },
                            update = { v ->
                                if (isNightMode) {
                                    v.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = ColorMatrixColorFilter(NIGHT_MODE_MATRIX) })
                                } else {
                                    v.setLayerType(View.LAYER_TYPE_NONE, null)
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = runtimeOpenError ?: openErrorMsg ?: "Unable to open this PDF.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(32.dp),
                            )
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Unable to open this PDF, the link is invalid.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ZoomButton(icon = { Icon(Icons.Default.Add, contentDescription = "Zoom in") }) {
                    pdfView?.let { v -> v.zoomWithAnimation((v.zoom * ZOOM_STEP).coerceAtMost(ZOOM_MAX)) }
                    showControls = true
                    controlsTouchTick++
                }
                ZoomButton(icon = { Icon(Icons.Default.Remove, contentDescription = "Zoom out") }) {
                    pdfView?.let { v -> v.zoomWithAnimation((v.zoom / ZOOM_STEP).coerceAtLeast(ZOOM_MIN)) }
                    showControls = true
                    controlsTouchTick++
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
                            Icon(Icons.Default.Search, contentDescription = "Search in document", tint = if (isViewerSearchActive) AmberAccent else MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = { showPageJump = true; controlsTouchTick++ }) {
                            Icon(Icons.Default.Pin, contentDescription = "Go to page")
                        }
                        IconButton(onClick = { showBrightnessSlider = !showBrightnessSlider; controlsTouchTick++ }) {
                            Icon(Icons.Default.BrightnessMedium, contentDescription = "Brightness", tint = if (showBrightnessSlider) AmberAccent else MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = { isNightMode = !isNightMode; controlsTouchTick++ }) {
                            Icon(imageVector = if (isNightMode) Icons.Default.LightMode else Icons.Default.DarkMode, contentDescription = null, tint = if (isNightMode) AmberAccent else MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = { viewModel.toggleScrollMode(); controlsTouchTick++ }) {
                            Icon(imageVector = if (scrollHorizontal) Icons.Default.SwapVert else Icons.Default.SwapHoriz, contentDescription = null)
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

private fun handlePdfLinkTap(
    event: LinkTapEvent,
    context: android.content.Context,
    pdfView: PDFView,
    onExternalLinkError: () -> Unit,
) {
    val uri = event.link?.uri
    if (!uri.isNullOrBlank()) {
        val normalized = if (uri.startsWith("http://", ignoreCase = true) || uri.startsWith("https://", ignoreCase = true)) {
            uri
        } else {
            "https://$uri"
        }
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(normalized)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: ActivityNotFoundException) {
            onExternalLinkError()
        }
        return
    }
    // Keep internal PDF link behavior (jump to destination/page) intact.
    DefaultLinkHandler(pdfView).handleLinkEvent(event)
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
