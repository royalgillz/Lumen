package com.lumen.app.ui.navigation

import android.app.Activity
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.lumen.app.BuildConfig
import com.lumen.app.domain.model.PendingSearch
import com.lumen.app.ui.documents.DocumentsScreen
import com.lumen.app.ui.eval.EvalScreen
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

    /** Debug builds only — never registered in release. @spec SEARCH-EVAL-001 */
    data object Eval : Screen("eval")
}

private const val PDF_VIEWER_ROUTE =
    "pdf_viewer?uri={uri}&page={page}&filename={filename}&keyword={keyword}&occ={occ}&req={req}"

// [deliveryId]: the external VIEW-intent request id (0 for in-app opens). A
// singleTop redelivery of the SAME document must change the route arguments,
// or the viewer's open effect never re-fires — a viewer sitting on the expired
// screen would silently ignore the fresh grant the redelivery carries.
// @spec VIEW-EXT-010
private fun pdfViewerRoute(
    uri: String,
    page: Int,
    filename: String,
    keyword: String = "",
    occurrence: Int = 0,
    deliveryId: Long = 0L,
): String =
    "pdf_viewer?uri=${Uri.encode(uri)}&page=$page" +
        "&filename=${Uri.encode(filename)}&keyword=${Uri.encode(keyword)}&occ=$occurrence&req=$deliveryId"

private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

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
    externalPdfName: String? = null,
    externalPdfRequestId: Long = 0L,
) {
    // Saveable: rotation and process restore replay the sticky intent under
    // its original request id, which must stay suppressed. Guarded by delivery
    // id rather than URI so re-sending the same document is a fresh delivery
    // that reopens the viewer.
    // @spec NAV-012
    val handledExternalRequestId = rememberSaveable { mutableStateOf(0L) }

    // One NavController and one graph for the activity's whole life: a layout
    // switch changes only derived values (bar, mode home, re-root rules) — no
    // navigation, no teardown, so the Settings screen under the toggle keeps
    // its entry, state, and ViewModels. The graph's start is frozen at first
    // composition; the caller's startDestination recomputes per mode and must
    // not reach the NavHost, or the graph would rebuild.
    // @spec NAV-004
    val graphStart = rememberSaveable { startDestination }
    // The stack's current root — updated only at the re-root sites (home-tab
    // tap, back guard, onboarding completion). Navigation instance state
    // restores the matching stack alongside it.
    // @spec NAV-014
    var stackRoot by rememberSaveable { mutableStateOf(graphStart) }

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val tabs = tabsFor(layoutMode)
    val tabRoutes = tabRoutesFor(layoutMode).toSet()

    // @spec NAV-012
    LaunchedEffect(externalPdfRequestId) {
        val uri = externalPdfUri ?: return@LaunchedEffect
        if (handledExternalRequestId.value == externalPdfRequestId) return@LaunchedEffect
        handledExternalRequestId.value = externalPdfRequestId
        val filename = externalPdfName?.takeIf { it.isNotBlank() }
            ?: runCatching { Uri.parse(uri).lastPathSegment }
                .getOrNull().orEmpty().ifBlank { "PDF" }
        navController.navigate(
            pdfViewerRoute(uri, page = 0, filename = filename, deliveryId = externalPdfRequestId)
        ) {
            launchSingleTop = true
        }
    }

    // A launcher search delivery (shortcut, widget, ACTION_PROCESS_TEXT) must
    // also SURFACE a search screen: the ViewModels apply the query wherever
    // they live, but the user may be resumed on Settings, Library, or inside
    // the viewer. Guarded like the external-PDF delivery above — saveable
    // handled id plus the request freshness window, so rotation and process
    // restore never re-navigate. Plain bar-shaped navigation; stackRoot and
    // the switch machinery are untouched.
    // @spec SEARCH-ENTRY-008
    val handledSearchRequestId = rememberSaveable { mutableStateOf(0L) }
    val pendingSearch by PendingSearch.request.collectAsState()
    LaunchedEffect(pendingSearch?.id) {
        val request = pendingSearch ?: return@LaunchedEffect
        if (handledSearchRequestId.value == request.id) return@LaunchedEffect
        if (!PendingSearch.isFresh(request)) return@LaunchedEffect
        handledSearchRequestId.value = request.id
        val home = modeHomeFor(layoutMode)
        if (currentRoute != home) {
            navController.navigate(home) { barNavOptions(stackRoot) }
        }
    }

    LumenScaffold(
        navController = navController,
        currentRoute = currentRoute,
        navBackStackEntry = navBackStackEntry,
        tabs = tabs,
        tabRoutes = tabRoutes,
        startDestination = graphStart,
        layoutMode = layoutMode,
        stackRoot = stackRoot,
        onStackRootChange = { stackRoot = it },
    )

    // A stale root (the mode changed since the stack was rooted) must never be
    // revealed by the system back control: on the mode home, back exits the
    // app; on any other tab, back retargets to the mode home. Composed AFTER
    // the scaffold: the back dispatcher is LIFO and the NavHost registers the
    // NavController's own pop callback during its composition — this guard
    // must register later to win while enabled. It is disabled on bar-less
    // routes, so the viewer's and onboarding's own BackHandlers keep priority
    // there.
    // NavLayoutSwitchRoboTest exercises this wiring through the same shared
    // functions; the harness there mirrors this host by hand and must be kept
    // in step.
    // @spec NAV-015
    val context = androidx.compose.ui.platform.LocalContext.current
    val backTarget = backGuardTarget(currentRoute, layoutMode, stackRoot)
    BackHandler(enabled = backTarget != null) {
        val target = backTarget ?: return@BackHandler
        if (currentRoute == target) {
            context.findActivity()?.finish()
        } else {
            navController.navigate(target) { barNavOptions(stackRoot) }
        }
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
    stackRoot: String,
    onStackRootChange: (String) -> Unit,
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
                                // @spec NAV-014
                                navController.navigate(tab.screen.route) {
                                    barNavOptions(stackRoot)
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
                    // @spec NAV-003 — the current mode's home, never a hardcoded
                    // route; completion roots the stack there (NAV-014).
                    val home = modeHomeFor(layoutMode)
                    navController.navigate(home) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                    onStackRootChange(home)
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
                            barNavOptions(stackRoot)
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
            // The applied layout mode drives the Navigation toggle's selected
            // state — the graph's own value can never go stale mid-switch.
            // @spec NAV-010
            composable(Screen.Settings.route) {
                SettingsScreen(
                    appliedNavLayout = layoutMode,
                    onOpenEval = { navController.navigate(Screen.Eval.route) },
                )
            }
            // One registration serves both layout modes (NAV-002 parity); the
            // route — and with it the whole eval surface — exists only in
            // debug builds.
            // @spec SEARCH-EVAL-001
            if (BuildConfig.DEBUG) {
                composable(Screen.Eval.route) {
                    EvalScreen(onBack = { navController.popBackStack() })
                }
            }
            composable(
                route = PDF_VIEWER_ROUTE,
                arguments = listOf(
                    navArgument("uri") { type = NavType.StringType; defaultValue = "" },
                    navArgument("page") { type = NavType.IntType; defaultValue = 0 },
                    navArgument("filename") { type = NavType.StringType; defaultValue = "" },
                    navArgument("keyword") { type = NavType.StringType; defaultValue = "" },
                    navArgument("occ") { type = NavType.IntType; defaultValue = 0 },
                    navArgument("req") { type = NavType.LongType; defaultValue = 0L },
                ),
            ) { backStackEntry ->
                val uri = backStackEntry.arguments?.getString("uri") ?: ""
                val page = backStackEntry.arguments?.getInt("page") ?: 0
                val filename = backStackEntry.arguments?.getString("filename") ?: ""
                val keyword = backStackEntry.arguments?.getString("keyword") ?: ""
                val occurrence = backStackEntry.arguments?.getInt("occ") ?: 0
                val deliveryId = backStackEntry.arguments?.getLong("req") ?: 0L
                PdfViewerScreen(
                    uri = uri,
                    pageNumber = page,
                    filename = filename,
                    keyword = keyword,
                    occurrence = occurrence,
                    deliveryId = deliveryId,
                    onBack = { navController.popBackStack() },
                    // A file rename changed the URI: replace this entry so the
                    // route arguments carry the new identity — rotation and
                    // process restore reopen the renamed file.
                    // @spec LIB-REN-008
                    onRenamed = { newUri, newFilename, currentPage ->
                        navController.navigate(
                            pdfViewerRoute(newUri, page = currentPage, filename = newFilename)
                        ) {
                            popUpTo(PDF_VIEWER_ROUTE) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
        }
    }
}
