package com.lumen.app.ui.documents

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.LazyListScope
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.lumen.app.data.db.entity.DocumentEntity
import com.lumen.app.ui.library.IndexHealthCard
import com.lumen.app.ui.library.RenameDocumentDialogHost
import com.lumen.app.ui.library.LibraryDocumentSheetHost
import com.lumen.app.ui.library.LibraryEmptyState
import com.lumen.app.ui.library.LibraryViewModel
import com.lumen.app.ui.library.LostAccessBanner
import com.lumen.app.ui.library.libraryDocumentsItems
import com.lumen.app.ui.library.sortLibrary
import com.lumen.app.ui.search.SearchScreen
import com.lumen.app.ui.search.SearchViewModel

/**
 * The 2-tab layout's merged tab: the search experience and the library body on
 * one scrollable surface. Everything here is the same shared component the
 * dedicated Search and Library screens render — this screen owns only the
 * composition. In 2-tab mode this is the search home: the SEARCH-UI specs
 * apply to it.
 */
// @spec NAV-005, NAV-006, NAV-007
@Composable
fun DocumentsScreen(
    searchViewModel: SearchViewModel = hiltViewModel(),
    libraryViewModel: LibraryViewModel = hiltViewModel(),
    onResultClick: (uri: String, page: Int, filename: String, keyword: String, occurrence: Int) -> Unit,
    onOpenDocument: (uri: String, filename: String, page: Int) -> Unit,
) {
    val documentsOrNull by libraryViewModel.documents.collectAsState()
    val foldersOrNull by libraryViewModel.folders.collectAsState()
    val isContentLoaded = documentsOrNull != null && foldersOrNull != null
    val documents = documentsOrNull.orEmpty()
    val folders = foldersOrNull.orEmpty()
    val folderStats by libraryViewModel.folderStats.collectAsState()
    val sortOrder by libraryViewModel.sortOrder.collectAsState()
    val bookmarkCounts by libraryViewModel.bookmarkCounts.collectAsState()
    val customTitles by libraryViewModel.customTitles.collectAsState()
    val lastPages by libraryViewModel.lastPages.collectAsState()
    val lostPermissionFolders by libraryViewModel.lostPermissionFolders.collectAsState()
    var gridMode by rememberSaveable { mutableStateOf(true) }
    var bookmarkedOnly by rememberSaveable { mutableStateOf(false) }
    // URI, not entity: survives rotation (rememberSaveable) and re-resolves
    // against the live list so a vanished document simply closes the dialog.
    // @spec LIB-TTL-010
    var renameTargetUri by rememberSaveable { mutableStateOf<String?>(null) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { libraryViewModel.addFolder(it) }
    }

    LibraryDocumentSheetHost(
        viewModel = libraryViewModel,
        onOpenDocument = onOpenDocument,
        onRenameSucceeded = { renameTargetUri = null },
    )

    // Loaded-list resolution: a target that vanished from the list (deleted,
    // or re-keyed by a completed rename) closes the dialog and clears the
    // saved state; a still-loading list (null) decides nothing yet.
    LaunchedEffect(documentsOrNull, renameTargetUri) {
        val loaded = documentsOrNull ?: return@LaunchedEffect
        if (renameTargetUri != null && loaded.none { it.uri == renameTargetUri }) {
            renameTargetUri = null
        }
    }

    renameTargetUri?.let { uri -> documents.firstOrNull { it.uri == uri } }?.let { doc ->
        RenameDocumentDialogHost(
            doc = doc,
            viewModel = libraryViewModel,
            onDismiss = { renameTargetUri = null },
        )
    }

    val libraryIdleContent: LazyListScope.() -> Unit = {
        if (isContentLoaded && folders.isEmpty() && documents.isEmpty()) {
            item { LibraryEmptyState(onPick = { folderPickerLauncher.launch(it) }) }
        } else if (isContentLoaded) {
            if (lostPermissionFolders.isNotEmpty()) {
                item { LostAccessBanner(folderCount = lostPermissionFolders.size) }
            }
            item {
                IndexHealthCard(
                    documents = documents,
                    folderStats = folderStats,
                    onReindexFolder = { libraryViewModel.reindexFolder(it) },
                    onOpenDocument = { doc -> onOpenDocument(doc.uri, doc.filename, 0) },
                    onRetryDocument = { libraryViewModel.retryDocument(it) },
                )
            }
            if (documents.isNotEmpty()) {
                val visibleDocuments = sortLibrary(
                    if (bookmarkedOnly) {
                        documents.filter { (bookmarkCounts[it.uri] ?: 0) > 0 }
                    } else {
                        documents
                    },
                    sortOrder,
                )
                libraryDocumentsItems(
                    visibleDocuments = visibleDocuments,
                    bookmarkCounts = bookmarkCounts,
                    customTitles = customTitles,
                    lastPages = lastPages,
                    bookmarkedOnly = bookmarkedOnly,
                    gridMode = gridMode,
                    sortOrder = sortOrder,
                    onToggleBookmarkedOnly = { bookmarkedOnly = !bookmarkedOnly },
                    onToggleGrid = { gridMode = !gridMode },
                    onSetSortOrder = { libraryViewModel.setSortOrder(it) },
                    onTapDocument = { doc: DocumentEntity -> libraryViewModel.showDocumentDetail(doc) },
                    onLongPressDocument = { renameTargetUri = it.uri },
                    onRetryDocument = { libraryViewModel.retryDocument(it) },
                )
            }
        }
    }

    SearchScreen(
        viewModel = searchViewModel,
        libraryViewModel = libraryViewModel,
        onResultClick = onResultClick,
        // The idle view IS the library here — clearing the query "opens" it.
        // @spec NAV-005
        onOpenLibrary = { searchViewModel.query.value = "" },
        showNoIndexPrompt = false,
        // This screen already hosts the detail sheet for this nav entry.
        hostDocumentSheet = false,
        extraIdleContent = libraryIdleContent,
    )
}
