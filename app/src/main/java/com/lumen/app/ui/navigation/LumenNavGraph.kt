package com.lumen.app.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.lumen.app.ui.documents.DocumentsScreen
import com.lumen.app.ui.library.LibraryScreen
import com.lumen.app.ui.onboarding.OnboardingScreen
import com.lumen.app.ui.onboarding.OnboardingViewModel
import com.lumen.app.ui.icons.LibraryTabIcon
import com.lumen.app.ui.icons.SearchTabIcon
import com.lumen.app.ui.search.SearchScreen
import com.lumen.app.ui.settings.SettingsScreen
import androidx.compose.material3.MaterialTheme
import com.lumen.app.ui.viewer.PdfViewerScreen

sealed class Screen(val route: String) {
    data object Onboarding : Screen("onboarding")
    data object Search : Screen("search")
    data object Library : Screen("library")
    data object Documents : Screen("documents")
    data object Settings : Screen("settings")
    data object PdfViewer : Screen("pdf_viewer")
}

private const val PDF_VIEWER_ROUTE =
    "pdf_viewer?uri={uri}&page={page}&filename={filename}&keyword={keyword}&occ={occ}"

private fun pdfViewerRoute(
    uri: String,
    page: Int,
    filename: String,
    keyword: String = "",
    occurrence: Int = 0,
): String =
    "pdf_viewer?uri=${Uri.encode(uri)}&page=$page" +
        "&filename=${Uri.encode(filename)}&keyword=${Uri.encode(keyword)}&occ=$occurrence"

private data class Tab(val screen: Screen, val label: String, val icon: @Composable (Boolean) -> Unit)

private val SEARCH_TAB = Tab(Screen.Search, "Search") { selected ->
    SearchTabIcon(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
}
private val LIBRARY_TAB = Tab(Screen.Library, "Library") { selected ->
    LibraryTabIcon(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
}
private val DOCUMENTS_TAB = Tab(Screen.Documents, "Documents") { selected ->
    LibraryTabIcon(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
}
private val SETTINGS_TAB = Tab(Screen.Settings, "Settings") { selected ->
    Icon(
        imageVector = Icons.Filled.Settings,
        contentDescription = null,
        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(24.dp),
    )
}

// @spec NAV-001
private fun tabsFor(layout: NavLayoutMode): List<Tab> = when (layout) {
    NavLayoutMode.THREE_TAB -> listOf(SEARCH_TAB, LIBRARY_TAB, SETTINGS_TAB)
    NavLayoutMode.TWO_TAB -> listOf(DOCUMENTS_TAB, SETTINGS_TAB)
}

@Composable
fun LumenNavGraph(
    startDestination: String = Screen.Search.route,
    layoutMode: NavLayoutMode = NavLayoutMode.THREE_TAB,
    externalPdfUri: String? = null,
) {
    // Outside key(): survives the graph rebuild on a layout switch so the same
    // external PDF is not pushed twice. Saveable: a plain remember reset on
    // rotation, so the effect below re-fired and pushed a second viewer.
    val handledExternalUri = rememberSaveable { mutableStateOf<String?>(null) }
    // Distinguishes a mid-session layout switch from first composition or
    // process restore — only the switch restores Settings.
    var lastLayoutMode by rememberSaveable { mutableStateOf<String?>(null) }

    // The graph is keyed on the layout: switching rebuilds it at the new mode's
    // start destination (back stack discarded), then restores Settings so the
    // user watches the bar change in place.
    // @spec NAV-004
    key(layoutMode) {
        val navController = rememberNavController()
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route
        val tabs = tabsFor(layoutMode)
        val tabRoutes = tabRoutesFor(layoutMode).toSet()

        LaunchedEffect(externalPdfUri) {
            val uri = externalPdfUri ?: return@LaunchedEffect
            if (handledExternalUri.value == uri) return@LaunchedEffect
            handledExternalUri.value = uri
            val filename = runCatching { Uri.parse(uri).lastPathSegment }
                .getOrNull().orEmpty().ifBlank { "PDF" }
            navController.navigate(pdfViewerRoute(uri, page = 0, filename = filename)) {
                launchSingleTop = true
            }
        }

        LaunchedEffect(Unit) {
            if (lastLayoutMode != null && lastLayoutMode != layoutMode.name) {
                navController.navigate(Screen.Settings.route) { launchSingleTop = true }
            }
            lastLayoutMode = layoutMode.name
        }

        LumenScaffold(
            navController = navController,
            currentRoute = currentRoute,
            navBackStackEntry = navBackStackEntry,
            tabs = tabs,
            tabRoutes = tabRoutes,
            startDestination = startDestination,
            layoutMode = layoutMode,
        )
    }
}

@Composable
private fun LumenScaffold(
    navController: NavHostController,
    currentRoute: String?,
    navBackStackEntry: androidx.navigation.NavBackStackEntry?,
    tabs: List<Tab>,
    tabRoutes: Set<String>,
    startDestination: String,
    layoutMode: NavLayoutMode,
) {
    Scaffold(
        bottomBar = {
            if (currentRoute in tabRoutes) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = navBackStackEntry?.destination
                            ?.hierarchy?.any { it.route == tab.screen.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { tab.icon(selected) },
                            label = {
                                Text(
                                    tab.label,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Screen.Onboarding.route) {
                val onboardingVm: OnboardingViewModel = hiltViewModel()
                OnboardingScreen(onFinished = {
                    onboardingVm.markDone()
                    // @spec NAV-003 — the current mode's home, never a hardcoded route.
                    navController.navigate(startDestinationFor(onboardingDone = true, layout = layoutMode)) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                })
            }
            composable(Screen.Search.route) {
                SearchScreen(
                    onResultClick = { uri, page, filename, keyword, occurrence ->
                        // singleTop so a double-tap can't stack two viewer copies.
                        navController.navigate(pdfViewerRoute(uri, page, filename, keyword, occurrence)) {
                            launchSingleTop = true
                        }
                    },
                    onOpenLibrary = {
                        navController.navigate(Screen.Library.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(Screen.Library.route) {
                LibraryScreen(
                    onOpenDocument = { uri, filename, page ->
                        navController.navigate(pdfViewerRoute(uri, page = page, filename = filename)) {
                            launchSingleTop = true
                        }
                    },
                )
            }
            // @spec NAV-002 — registered in both modes; only the bar varies.
            composable(Screen.Documents.route) {
                DocumentsScreen(
                    onResultClick = { uri, page, filename, keyword, occurrence ->
                        navController.navigate(pdfViewerRoute(uri, page, filename, keyword, occurrence)) {
                            launchSingleTop = true
                        }
                    },
                    onOpenDocument = { uri, filename, page ->
                        navController.navigate(pdfViewerRoute(uri, page = page, filename = filename)) {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(Screen.Settings.route) { SettingsScreen() }
            composable(
                route = PDF_VIEWER_ROUTE,
                arguments = listOf(
                    navArgument("uri") { type = NavType.StringType; defaultValue = "" },
                    navArgument("page") { type = NavType.IntType; defaultValue = 0 },
                    navArgument("filename") { type = NavType.StringType; defaultValue = "" },
                    navArgument("keyword") { type = NavType.StringType; defaultValue = "" },
                    navArgument("occ") { type = NavType.IntType; defaultValue = 0 },
                ),
            ) { backStackEntry ->
                val uri = backStackEntry.arguments?.getString("uri") ?: ""
                val page = backStackEntry.arguments?.getInt("page") ?: 0
                val filename = backStackEntry.arguments?.getString("filename") ?: ""
                val keyword = backStackEntry.arguments?.getString("keyword") ?: ""
                val occurrence = backStackEntry.arguments?.getInt("occ") ?: 0
                PdfViewerScreen(
                    uri = uri,
                    pageNumber = page,
                    filename = filename,
                    keyword = keyword,
                    occurrence = occurrence,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
