package com.lumen.app.ui.viewer

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.listener.OnPageChangeListener
import com.lumen.app.ui.theme.AmberAccent
import java.io.FileNotFoundException

@Composable
fun PdfViewerScreen(
    uri: String,
    pageNumber: Int,
    filename: String,
    keyword: String = "",
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val parsedUri = remember(uri) { runCatching { Uri.parse(uri) }.getOrNull() }

    // Open the stream, capturing the specific error type so we can show a useful message.
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

    DisposableEffect(stream) {
        onDispose { stream?.close() }
    }

    val displayPage = remember { mutableIntStateOf(pageNumber) }
    val pageCount = remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(stream != null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                )
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (keyword.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
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
            }
        }
        HorizontalDivider()

        if (stream != null) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        PDFView(ctx, null).also { pdfView ->
                            pdfView.fromStream(stream)
                                .defaultPage(pageNumber)
                                .onPageChange(object : OnPageChangeListener {
                                    override fun onPageChanged(page: Int, count: Int) {
                                        displayPage.intValue = page
                                        pageCount.intValue = count
                                    }
                                })
                                .onLoad { _ -> isLoading = false }
                                .onError { _ -> isLoading = false }
                                .enableSwipe(true)
                                .swipeHorizontal(false)
                                .enableDoubletap(true)
                                .enableAnnotationRendering(true)
                                .load()
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
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
