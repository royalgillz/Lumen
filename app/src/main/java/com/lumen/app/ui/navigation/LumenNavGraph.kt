package com.lumen.app.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.hilt.navigation.compose.hiltViewModel
import com.lumen.app.ui.library.LibraryScreen
import com.lumen.app.ui.onboarding.OnboardingScreen
import com.lumen.app.ui.onboarding.OnboardingViewModel
import com.lumen.app.ui.search.SearchScreen
import com.lumen.app.ui.settings.SettingsScreen
import com.lumen.app.ui.viewer.PdfViewerScreen

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

private data class Tab(val screen: Screen, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(Screen.Search, "Search", Icons.Default.Search),
    Tab(Screen.Library, "Library", Icons.Default.Book),
    Tab(Screen.Settings, "Settings", Icons.Default.Settings),
)

private val TAB_ROUTES = TABS.map { it.screen.route }.toSet()

@Composable
fun LumenNavGraph(
    startDestination: String = Screen.Search.route,
    navController: NavHostController = rememberNavController(),
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

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
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
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
            composable(Screen.Library.route) { LibraryScreen() }
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
