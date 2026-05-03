package com.lumen.app.ui.viewer

import android.app.Activity
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.core.view.WindowCompat
import java.util.concurrent.atomic.AtomicReference
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.listener.OnPageChangeListener
import com.lumen.app.ui.theme.AmberAccent
import kotlinx.coroutines.launch
import java.io.FileNotFoundException

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
    val scope = rememberCoroutineScope()
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

    // Snackbar for reading-progress resume
    val snackbarHostState = remember { SnackbarHostState() }

    val scrollHorizontal by viewModel.scrollHorizontal.collectAsState()
    val highlights by viewModel.highlights.collectAsState()
    val matchPages by viewModel.viewerMatchPages.collectAsState()
    val matchIndex by viewModel.viewerMatchIndex.collectAsState()

    // ── Status bar: force light icons against the dark primary top bar ───────
    SideEffect {
        val window = (view.context as Activity).window
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
    }
    DisposableEffect(Unit) {
        onDispose {
            val window = (view.context as Activity).window
            val lp = window.attributes
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = lp
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDarkTheme
        }
    }

    // ── Brightness effects ────────────────────────────────────────────────────
    LaunchedEffect(brightness, showBrightnessSlider) {
        if (showBrightnessSlider) {
            val window = (view.context as Activity).window
            val lp = window.attributes
            lp.screenBrightness = brightness
            window.attributes = lp
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
                if (keyword.isNotBlank()) viewModel.loadHighlights(uri, lastPage, keyword)
            }
        }
    }

    // ── Initial keyword highlights ────────────────────────────────────────────
    LaunchedEffect(uri, pageNumber, keyword) {
        viewModel.loadHighlights(uri, pageNumber, keyword)
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
            viewModel.loadHighlights(uri, page, viewerSearchText.ifBlank { keyword })
        }
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
                        if (keyword.isNotBlank()) viewModel.loadHighlights(uri, target, keyword)
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // ── Top bar ───────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primary)
                .statusBarsPadding()
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onPrimary)
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = filename,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (pageCount.intValue > 0) {
                    Text(
                        text = "p. ${displayPage.intValue + 1} of ${pageCount.intValue}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f),
                        modifier = Modifier.clickable { showPageJump = true },
                    )
                }
            }

            if (keyword.isNotBlank() && !isViewerSearchActive) {
                Text(
                    text = keyword.take(20).let { if (keyword.length > 20) "$it…" else it },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = AmberAccent,
                    maxLines = 1,
                    modifier = Modifier
                        .background(AmberAccent.copy(alpha = 0.20f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Spacer(Modifier.width(4.dp))
            }

            // In-viewer search toggle
            IconButton(onClick = {
                isViewerSearchActive = !isViewerSearchActive
                if (!isViewerSearchActive) {
                    viewerSearchText = ""
                    viewModel.searchInDocument(uri, "")
                }
            }) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = "Search in document",
                    tint = if (isViewerSearchActive) AmberAccent
                           else MaterialTheme.colorScheme.onPrimary,
                )
            }

            // Brightness toggle
            IconButton(onClick = { showBrightnessSlider = !showBrightnessSlider }) {
                Icon(
                    Icons.Default.BrightnessMedium,
                    contentDescription = "Brightness",
                    tint = if (showBrightnessSlider) AmberAccent else MaterialTheme.colorScheme.onPrimary,
                )
            }

            // Night mode toggle
            IconButton(onClick = { isNightMode = !isNightMode }) {
                Icon(
                    imageVector = if (isNightMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                    contentDescription = if (isNightMode) "Day mode" else "Night mode",
                    tint = if (isNightMode) AmberAccent else MaterialTheme.colorScheme.onPrimary,
                )
            }

            // Scroll mode toggle
            IconButton(onClick = { viewModel.toggleScrollMode() }) {
                Icon(
                    imageVector = if (scrollHorizontal) Icons.Default.SwapVert else Icons.Default.SwapHoriz,
                    contentDescription = if (scrollHorizontal) "Switch to vertical scroll" else "Switch to horizontal scroll",
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }

        // ── In-viewer search bar ──────────────────────────────────────────────
        AnimatedVisibility(
            visible = isViewerSearchActive,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    value = viewerSearchText,
                    onValueChange = { text ->
                        viewerSearchText = text
                        viewModel.searchInDocument(uri, text)
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
                    IconButton(onClick = { viewModel.prevMatch() }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous match", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { viewModel.nextMatch() }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next match", modifier = Modifier.size(20.dp))
                    }
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
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close search", modifier = Modifier.size(18.dp))
                }
            }
        }

        // ── Brightness slider ─────────────────────────────────────────────────
        AnimatedVisibility(
            visible = showBrightnessSlider,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.BrightnessLow, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = brightness,
                    onValueChange = { brightness = it },
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

        HorizontalDivider()

        // ── PDF content ───────────────────────────────────────────────────────
        Box(modifier = Modifier.fillMaxSize()) {
            if (parsedUri != null) {
                // key() forces full rebuild of PDFView when scroll mode changes
                key(scrollHorizontal) {
                    val (stream, openErrorMsg) = remember(parsedUri) {
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

                    if (stream != null) {
                        AndroidView(
                            factory = { ctx ->
                                PDFView(ctx, null).also { v ->
                                    pdfView = v
                                    isLoading = true
                                    v.fromStream(stream)
                                        .defaultPage(displayPage.intValue)
                                        .swipeHorizontal(scrollHorizontal)
                                        .onPageChange(object : OnPageChangeListener {
                                            override fun onPageChanged(page: Int, count: Int) {
                                                displayPage.intValue = page
                                                pageCount.intValue = count
                                                viewModel.saveLastPage(uri, page)
                                                if (keyword.isNotBlank()) {
                                                    viewModel.loadHighlights(uri, page, keyword)
                                                }
                                            }
                                        })
                                        .onLoad { _ -> isLoading = false }
                                        .onError { isLoading = false }
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
                                text = openErrorMsg ?: "Unable to open this PDF.",
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
                        "Unable to open this PDF — the link is invalid.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            }

            // ── Zoom controls ─────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ZoomButton(icon = { Icon(Icons.Default.Add, contentDescription = "Zoom in") }) {
                    pdfView?.let { v -> v.zoomWithAnimation((v.zoom * ZOOM_STEP).coerceAtMost(ZOOM_MAX)) }
                }
                ZoomButton(icon = { Icon(Icons.Default.Remove, contentDescription = "Zoom out") }) {
                    pdfView?.let { v -> v.zoomWithAnimation((v.zoom / ZOOM_STEP).coerceAtLeast(ZOOM_MIN)) }
                }
            }

            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.primary)
            }

            SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))
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
