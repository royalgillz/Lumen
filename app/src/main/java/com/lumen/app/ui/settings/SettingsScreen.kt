package com.lumen.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.ui.text.style.TextOverflow
import com.lumen.app.BuildConfig
import com.lumen.app.domain.model.ScorerVariant
import com.lumen.app.ui.common.folderDisplayName
import com.lumen.app.ui.navigation.NavLayoutMode
import com.lumen.app.ui.theme.ThemeMode
import com.lumen.app.ui.icons.LumenBrandIcon
import com.lumen.app.ui.icons.PrivacyIcon
import com.lumen.app.ui.icons.SearchDocIcon
import com.lumen.app.ui.icons.TrashIcon
import com.lumen.app.ui.theme.Terracotta

@Composable
fun SettingsScreen(
    // The layout the navigation host has applied — the toggle's selected state
    // derives from it, never from a flow that can replay a stale value while a
    // switch is rebuilding the graph.
    // @spec NAV-010
    appliedNavLayout: NavLayoutMode = NavLayoutMode.THREE_TAB,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    // Saveable so confirmation dialogs survive rotation; the Uri rides as a string.
    // @spec SET-DATA-002
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var folderPendingRemoval by rememberSaveable { mutableStateOf<String?>(null) }
    val haptic = LocalHapticFeedback.current
    val folders by viewModel.folders.collectAsState()
    val lostPermissionFolders by viewModel.lostPermissionFolders.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val scorerVariant by viewModel.scorerVariant.collectAsState()

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { viewModel.addFolder(it) }
    }

    val removalTarget = folderPendingRemoval?.let(Uri::parse)
    if (removalTarget != null) {
        AlertDialog(
            onDismissRequest = { folderPendingRemoval = null },
            title = { Text("Remove folder?") },
            text = {
                Text(
                    "“${folderDisplayName(removalTarget)}” and its documents will be " +
                        "removed from the search index. The files stay on your phone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.removeFolder(removalTarget)
                        folderPendingRemoval = null
                    }
                ) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { folderPendingRemoval = null }) { Text("Cancel") }
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete search index?") },
            text = {
                Text(
                    // @spec LIB-REC-006
                    "All indexed content and your recently-opened history will be removed. " +
                        "Your PDF files are not affected. You can re-index at any time from the Library."
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

        SectionLabel("Appearance")
        AppearanceCard(
            themeMode = themeMode,
            navLayout = appliedNavLayout,
            onThemeChange = { viewModel.setThemeMode(it) },
            onNavLayoutChange = { viewModel.setNavLayout(it) },
        )
        Spacer(Modifier.height(8.dp))

        SectionLabel("Privacy")
        PrivacyDetailsCard()
        Spacer(Modifier.height(16.dp))

        SectionLabel("Data")
        DataCard(onDeleteIndex = { showDeleteConfirm = true })
        Spacer(Modifier.height(12.dp))
        IndexedFoldersCard(
            folders = folders,
            lostPermissionFolders = lostPermissionFolders,
            onAddFolder = { folderPickerLauncher.launch(null) },
            onReindexFolder = { viewModel.reindexFolder(it) },
            onRemoveFolder = { folderPendingRemoval = it.toString() },
        )
        Spacer(Modifier.height(16.dp))

        SectionLabel("About")
        AboutCard()
        Spacer(Modifier.height(24.dp))

        // Debug-only: ranking scorer A/B toggle. Never present in release
        // builds — release always ranks with the production default.
        // @spec SEARCH-RANK-007
        if (BuildConfig.DEBUG) {
            SectionLabel("Debug")
            DebugCard(
                scorerVariant = scorerVariant,
                onScorerVariantChange = { viewModel.setScorerVariant(it) },
            )
            Spacer(Modifier.height(24.dp))
        }

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
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

// Theme and navigation-layout choices as single-choice segmented rows: two or
// three mutually exclusive options are clearest side by side. Persisted and
// applied immediately — the user watches the bottom bar change under them.
// @spec SET-APPEAR-001
/** Debug builds only (see the BuildConfig.DEBUG gate at the call site). */
@Composable
private fun DebugCard(
    scorerVariant: ScorerVariant,
    onScorerVariantChange: (ScorerVariant) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Search ranking", style = MaterialTheme.typography.bodyMedium)
            Text(
                "A/B the content-lane scorer. Release builds always use BM25.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val options = listOf(
                    ScorerVariant.CURRENT to "Hit count",
                    ScorerVariant.BM25 to "BM25",
                )
                options.forEachIndexed { index, (variant, label) ->
                    SegmentedButton(
                        selected = scorerVariant == variant,
                        onClick = { onScorerVariantChange(variant) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    ) { Text(label) }
                }
            }
        }
    }
}

@Composable
private fun AppearanceCard(
    themeMode: ThemeMode,
    navLayout: NavLayoutMode,
    onThemeChange: (ThemeMode) -> Unit,
    onNavLayoutChange: (NavLayoutMode) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Theme", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Dark theme changes the app, never the pages of your PDFs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val options = listOf(
                    ThemeMode.SYSTEM to "System",
                    ThemeMode.LIGHT to "Light",
                    ThemeMode.DARK to "Dark",
                )
                options.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = themeMode == mode,
                        onClick = { onThemeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    ) { Text(label) }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Navigation", style = MaterialTheme.typography.bodyMedium)
            Text(
                "2 tabs merges Search and Library into one Documents screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val options = listOf(
                    NavLayoutMode.THREE_TAB to "3 tabs",
                    NavLayoutMode.TWO_TAB to "2 tabs",
                )
                options.forEachIndexed { index, (layout, label) ->
                    SegmentedButton(
                        selected = navLayout == layout,
                        onClick = { onNavLayoutChange(layout) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    ) { Text(label) }
                }
            }
        }
    }
}

// The app stating its properties — "details", not "Audit": an audit is
// something a third party performs, and overclaiming the word costs
// credibility with exactly the audience this card serves. Four claims, each
// stated once; capability facts (OCR languages) and licensing live in About.
// @spec SET-PRIV-001, SET-PRIV-002
@Composable
private fun PrivacyDetailsCard() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Privacy details",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            PrivacyRow(icon = { tint -> PrivacyIcon(tint) }, "No internet permission", "The app declares no network permission in its manifest, so it cannot make requests even if it tried.", isGood = true)
            PrivacyRow(icon = { tint -> SearchDocIcon(tint) }, "No analytics or crash reporting", "No Firebase, no Sentry, no SDK that phones home.", isGood = true)
            PrivacyRow(icon = { tint -> PrivacyIcon(tint) }, "Files read in place", "PDFs are never copied into app storage. Lumen reads them where they already live.", isGood = true)
            PrivacyRow(icon = { tint -> SearchDocIcon(tint) }, "Index stored on device only", "The full-text index is a local SQLite database, excluded from system backups. On a new phone, re-add your folders to rebuild it.", isGood = true)
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
                "Deletes all indexed text from your PDFs and clears your recently-opened history. " +
                    "Your actual PDF files are not affected. " +
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

/**
 * Collapsed by default: a one-line "Indexed Folders · N" row that expands to the
 * folder list with re-index/remove actions and an add button. The header shows a
 * warning icon whenever any folder has lost its storage permission, so problems
 * are visible without expanding.
 */
@Composable
private fun IndexedFoldersCard(
    folders: Set<Uri>,
    lostPermissionFolders: Set<Uri>,
    onAddFolder: () -> Unit,
    onReindexFolder: (Uri) -> Unit,
    onRemoveFolder: (Uri) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    "Indexed Folders · ${folders.size}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                )
                if (lostPermissionFolders.isNotEmpty()) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = "${lostPermissionFolders.size} folders lost access",
                        tint = Terracotta,
                        modifier = Modifier
                            .size(18.dp)
                            .padding(end = 2.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, bottom = 12.dp)) {
                    Text(
                        "Folders Lumen scans for PDFs. Removing one deletes its documents " +
                            "from the index, not from your phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    folders.forEach { uri ->
                        val lost = uri in lostPermissionFolders
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (lost) Icons.Default.Warning else Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = if (lost) Terracotta else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 10.dp),
                            ) {
                                Text(
                                    folderDisplayName(uri),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (lost) {
                                    Text(
                                        "Permission lost — remove and re-add",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Terracotta,
                                    )
                                }
                            }
                            if (!lost) {
                                IconButton(onClick = { onReindexFolder(uri) }) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = "Re-index folder",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            IconButton(onClick = { onRemoveFolder(uri) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Remove folder",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                    if (folders.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                    TextButton(onClick = onAddFolder) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add folder", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

// The app's factual footer: version, capability, and licensing. The source URL
// is a real affordance — tappable (the browser makes the request; Lumen still
// holds no network permission) with a copy fallback for readers who want to
// inspect before opening. This row carries the MuPDF AGPL attribution.
// @spec SET-ABOUT-001, SET-ABOUT-002, SET-ABOUT-003
@Composable
private fun AboutCard() {
    val context = LocalContext.current
    val repoUrl = "https://github.com/royalgillz/Lumen"

    fun copyUrl() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Lumen source", repoUrl))
    }

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

            Spacer(Modifier.height(12.dp))
            Text("OCR languages", style = MaterialTheme.typography.bodyMedium)
            Text(
                "OCR recognises Latin-script text fully on-device. Other scripts aren't supported yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            Text("Source & licenses", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Lumen is open source (AGPL-3.0). PDF rendering by MuPDF (AGPL).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "github.com/royalgillz/Lumen",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(repoUrl)))
                            } catch (_: ActivityNotFoundException) {
                                copyUrl()
                                // Always shown: it explains WHY nothing opened,
                                // which the 13+ clipboard overlay doesn't convey.
                                Toast.makeText(context, "No browser found — URL copied", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .padding(vertical = 8.dp),
                )
                IconButton(onClick = {
                    copyUrl()
                    // Android 13+ shows its own clipboard overlay; a toast there duplicates it.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy source URL",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
