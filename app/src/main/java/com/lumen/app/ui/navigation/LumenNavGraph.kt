package com.lumen.app.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import com.lumen.app.ui.library.LibraryScreen
import com.lumen.app.ui.onboarding.OnboardingScreen
import com.lumen.app.ui.onboarding.OnboardingViewModel
import com.lumen.app.ui.icons.LibraryTabIcon
import com.lumen.app.ui.icons.SearchTabIcon
import com.lumen.app.ui.icons.SettingsTabIcon
import com.lumen.app.ui.search.SearchScreen
import com.lumen.app.ui.settings.SettingsScreen
import androidx.compose.material3.MaterialTheme
import com.lumen.app.ui.viewer.PdfViewerScreen
import com.lumen.app.ui.theme.AmberAccent

sealed class Screen(val route: String) {
    data object Onboarding : Screen("onboarding")
    data object Search : Screen("search")
    data object Library : Screen("library")
    data object Settings : Screen("settings")
    data object PdfViewer : Screen("pdf_viewer")
}

private const val PDF_VIEWER_ROUTE =
    "pdf_viewer?uri={uri}&page={page}&filename={filename}&keyword={keyword}"

fun pdfViewerRoute(uri: String, page: Int, filename: String, keyword: String = ""): String =
    "pdf_viewer?uri=${Uri.encode(uri)}&page=$page" +
    "&filename=${Uri.encode(filename)}&keyword=${Uri.encode(keyword)}"

private data class Tab(val screen: Screen, val label: String, val icon: @Composable (Boolean) -> Unit)

private val TABS = listOf(
    Tab(Screen.Search, "Search") { selected ->
        SearchTabIcon(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    },
    Tab(Screen.Library, "Library") { selected ->
        LibraryTabIcon(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    },
    Tab(Screen.Settings, "Settings") { selected ->
        SettingsTabIcon(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    },
)

private val TAB_ROUTES = TABS.map { it.screen.route }.toSet()

@Composable
fun LumenNavGraph(
    startDestination: String = Screen.Search.route,
    externalPdfUri: String? = null,
    navController: NavHostController = rememberNavController(),
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val handledExternalUri = remember { mutableStateOf<String?>(null) }

    LaunchedEffect(externalPdfUri) {
        val uri = externalPdfUri ?: return@LaunchedEffect
        if (handledExternalUri.value == uri) return@LaunchedEffect
        handledExternalUri.value = uri
        val filename = runCatching { Uri.parse(uri).lastPathSegment }.getOrNull().orEmpty().ifBlank { "PDF" }
        navController.navigate(pdfViewerRoute(uri, page = 0, filename = filename, keyword = "")) {
            launchSingleTop = true
        }
    }

    Scaffold(
        bottomBar = {
            if (currentRoute in TAB_ROUTES) {
                NavigationBar {
                    TABS.forEach { tab ->
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
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = AmberAccent.copy(alpha = 0.18f),
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = AmberAccent,
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
                    navController.navigate(Screen.Search.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                })
            }
            composable(Screen.Search.route) {
                SearchScreen(
                    onResultClick = { uri, page, filename, keyword ->
                        navController.navigate(pdfViewerRoute(uri, page, filename, keyword))
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
                    onOpenDocument = { uri, filename ->
                        navController.navigate(pdfViewerRoute(uri, page = 0, filename = filename, keyword = ""))
                    }
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
                ),
            ) { backStackEntry ->
                val uri = backStackEntry.arguments?.getString("uri") ?: ""
                val page = backStackEntry.arguments?.getInt("page") ?: 0
                val filename = backStackEntry.arguments?.getString("filename") ?: ""
                val keyword = backStackEntry.arguments?.getString("keyword") ?: ""
                PdfViewerScreen(
                    uri = uri,
                    pageNumber = page,
                    filename = filename,
                    keyword = keyword,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
