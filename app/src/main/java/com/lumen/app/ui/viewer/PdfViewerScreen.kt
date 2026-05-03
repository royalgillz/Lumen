package com.lumen.app.ui.viewer

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.listener.OnPageChangeListener
import com.lumen.app.ui.theme.AmberAccent
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
    val parsedUri = remember(uri) { runCatching { Uri.parse(uri) }.getOrNull() }

    val (stream, openErrorMsg) = remember(parsedUri) {
        if (parsedUri == null) {
            Pair(null, "Unable to open this PDF — the link is invalid.")
        } else {
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
    }

    DisposableEffect(stream) { onDispose { stream?.close() } }

    // Viewer state
    val displayPage = remember { mutableIntStateOf(pageNumber) }
    val pageCount = remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(stream != null) }
    var isNightMode by remember { mutableStateOf(false) }
    var showPageJump by remember { mutableStateOf(false) }
    var pageJumpInput by remember { mutableStateOf("") }

    // Rendered page size (pts from PdfBox, px from PDFView after load)
    var renderedPageWidthPx by remember { mutableFloatStateOf(0f) }
    var renderedPageHeightPx by remember { mutableFloatStateOf(0f) }

    // Reference to the PDFView for programmatic zoom/jump
    var pdfView by remember { mutableStateOf<PDFView?>(null) }

    // Highlights from ViewModel
    val highlights by viewModel.highlights.collectAsState()

    // Load highlights for the initial page
    LaunchedEffect(uri, pageNumber, keyword) {
        viewModel.loadHighlights(uri, pageNumber, keyword)
    }

    // Page-jump dialog
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
                TextButton(onClick = {
                    showPageJump = false
                    pageJumpInput = ""
                }) { Text("Cancel") }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // ── Top bar ────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
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
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { showPageJump = true },
                    )
                }
            }

            if (keyword.isNotBlank()) {
                Text(
                    text = keyword.take(20).let { if (keyword.length > 20) "$it…" else it },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = AmberAccent,
                    maxLines = 1,
                    modifier = Modifier
                        .background(AmberAccent.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Spacer(Modifier.width(6.dp))
            }

            IconButton(onClick = { isNightMode = !isNightMode }) {
                Icon(
                    imageVector = if (isNightMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                    contentDescription = if (isNightMode) "Day mode" else "Night mode",
                    tint = if (isNightMode) AmberAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()

        // ── Content ────────────────────────────────────────────────────────────
        if (stream != null) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        PDFView(ctx, null).also { view ->
                            pdfView = view
                            view.fromStream(stream)
                                .defaultPage(pageNumber)
                                .onPageChange(object : OnPageChangeListener {
                                    override fun onPageChanged(page: Int, count: Int) {
                                        displayPage.intValue = page
                                        pageCount.intValue = count
                                        // Reload highlights when user navigates to a different page
                                        if (keyword.isNotBlank() && page != pageNumber) {
                                            viewModel.loadHighlights(uri, page, keyword)
                                        }
                                    }
                                })
                                .onLoad { _ ->
                                    isLoading = false
                                    // Capture rendered page width after load (page fills view width at zoom 1)
                                    renderedPageWidthPx = view.width.toFloat()
                                    renderedPageHeightPx = view.getPageSize(pageNumber).height
                                }
                                .onError { _ -> isLoading = false }
                                .enableSwipe(true)
                                .swipeHorizontal(false)
                                .enableDoubletap(true)
                                .enableAnnotationRendering(true)
                                .load()
                        }
                    },
                    update = { view ->
                        // Apply/remove night mode via hardware color filter
                        if (isNightMode) {
                            view.setLayerType(
                                View.LAYER_TYPE_HARDWARE,
                                Paint().apply { colorFilter = ColorMatrixColorFilter(NIGHT_MODE_MATRIX) },
                            )
                        } else {
                            view.setLayerType(View.LAYER_TYPE_NONE, null)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                // ── Highlight overlay ──────────────────────────────────────────
                val h = highlights
                if (h != null && h.rects.isNotEmpty() && h.pageWidthPts > 0f && renderedPageWidthPx > 0f) {
                    // Uniform scale: page is rendered to fit the view width
                    val scale = renderedPageWidthPx / h.pageWidthPts
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawWithContent {
                                drawContent()
                                for (rect in h.rects) {
                                    drawRect(
                                        color = AmberAccent.copy(alpha = if (isNightMode) 0.55f else 0.35f),
                                        topLeft = Offset(rect.left * scale, rect.top * scale),
                                        size = Size(
                                            (rect.right - rect.left) * scale,
                                            (rect.bottom - rect.top) * scale,
                                        ),
                                    )
                                }
                            },
                    )
                }

                // ── Zoom controls ──────────────────────────────────────────────
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ZoomButton(icon = { Icon(Icons.Default.Add, contentDescription = "Zoom in") }) {
                        pdfView?.let { v ->
                            v.zoomWithAnimation((v.zoom * ZOOM_STEP).coerceAtMost(ZOOM_MAX))
                        }
                    }
                    ZoomButton(icon = { Icon(Icons.Default.Remove, contentDescription = "Zoom out") }) {
                        pdfView?.let { v ->
                            v.zoomWithAnimation((v.zoom / ZOOM_STEP).coerceAtLeast(ZOOM_MIN))
                        }
                    }
                }

                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
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
