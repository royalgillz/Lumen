package com.lumen.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.lumen.app.BuildConfig
import com.lumen.app.ui.icons.LumenBrandIcon
import com.lumen.app.ui.icons.PrivacyIcon
import com.lumen.app.ui.icons.SearchDocIcon
import com.lumen.app.ui.icons.TrashIcon

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val indexedCount by viewModel.indexedCount.collectAsState()
    val totalPages by viewModel.totalPages.collectAsState()
    val totalWords by viewModel.totalWords.collectAsState()
    val ocrPages by viewModel.ocrPages.collectAsState()

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete search index?") },
            text = {
                Text(
                    "All indexed content will be removed. Your PDF files are not affected. " +
                        "You can re-index at any time from the Library."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.deleteIndex()
                        showDeleteConfirm = false
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 1.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    LumenBrandIcon()
                }
                Column {
                    Text("Lumen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Private, offline PDF search",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        SectionLabel("Privacy")
        PrivacyAuditCard()
        Spacer(Modifier.height(16.dp))

        SectionLabel("Index Health")
        IndexHealthCard(
            indexedCount = indexedCount,
            totalPages = totalPages,
            totalWords = totalWords,
            ocrPages = ocrPages,
        )
        Spacer(Modifier.height(16.dp))

        SectionLabel("Data")
        DataCard(onDeleteIndex = { showDeleteConfirm = true })
        Spacer(Modifier.height(16.dp))

        SectionLabel("About")
        AboutCard()
        Spacer(Modifier.height(24.dp))

        Text(
            text = "Lumen collects no data. No network requests. Ever.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        )
    }
}

@Composable
private fun IndexHealthCard(
    indexedCount: Int,
    totalPages: Int,
    totalWords: Int,
    ocrPages: Int,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Library index", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            MetricRow("Indexed PDFs", indexedCount.toString())
            MetricRow("Total pages", formatCount(totalPages))
            MetricRow("Total words", formatCount(totalWords))
            MetricRow("OCR coverage", if (totalPages > 0) "${ocrPages * 100 / totalPages}%" else "0%")
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatCount(value: Int): String = when {
    value >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", value / 1_000_000f)
    value >= 1_000 -> String.format(java.util.Locale.US, "%.1fk", value / 1_000f)
    else -> value.toString()
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun PrivacyAuditCard() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Privacy Audit",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            PrivacyRow(icon = { tint -> PrivacyIcon(tint) }, "No internet permission", "This app cannot make network requests, ever.", isGood = true)
            PrivacyRow(icon = { tint -> SearchDocIcon(tint) }, "No analytics or crash reporting", "No Firebase, Sentry, or any SDK that phones home.", isGood = true)
            PrivacyRow(icon = { tint -> PrivacyIcon(tint) }, "Files read in-place", "PDFs are never copied into app storage.", isGood = true)
            PrivacyRow(icon = { tint -> SearchDocIcon(tint) }, "Index stored on-device only", "Full-text index lives in a local SQLite database.", isGood = true)
            PrivacyRow(icon = { tint -> SearchDocIcon(tint) }, "AGPL source availability", "Source code available at github.com/royalgillz/Lumen", isGood = true)
        }
    }
}

@Composable
private fun PrivacyRow(icon: @Composable (Color) -> Unit, title: String, subtitle: String, isGood: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        icon(if (isGood) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DataCard(onDeleteIndex: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Search Index", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Deletes all indexed text from your PDFs. Your actual PDF files are not affected. " +
                    "You can re-index any folder from the Library tab.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDeleteIndex()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                TrashIcon(
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Delete Search Index")
            }
        }
    }
}

@Composable
private fun AboutCard() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Lumen", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Version ${BuildConfig.VERSION_NAME} · Privacy-first offline PDF search",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
