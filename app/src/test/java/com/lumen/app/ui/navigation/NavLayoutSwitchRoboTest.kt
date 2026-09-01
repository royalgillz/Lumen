package com.lumen.app.ui.navigation

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Exercises the no-teardown layout switch against the real NavController and
 * Compose runtime, with stub screens standing in for the Hilt-backed ones.
 * The re-root rules and navigation options come from the SAME production
 * functions LumenNavGraph uses (modeHomeFor, reRootsOnTab, backGuardTarget,
 * barNavOptions); only the host wiring is mirrored here and must be kept in
 * step with LumenNavGraph by hand.
 */
// @spec NAV-004, NAV-012, NAV-014, NAV-015
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class NavLayoutSwitchRoboTest {

    @get:Rule
    val rule = createComposeRule()

    private class Capture {
        var navController: NavHostController? = null
        var currentEntry: NavBackStackEntry? = null
        var stackRoot: String? = null
        var backDispatcher: OnBackPressedDispatcher? = null
        var exitRequested: Boolean = false
    }

    @Composable
    private fun Harness(
        layoutMode: NavLayoutMode,
        startDestination: String,
        capture: Capture,
        externalPdfUri: String? = null,
        externalPdfRequestId: Long = 0L,
    ) {
        val handledExternalRequestId = rememberSaveable { mutableStateOf(0L) }
        val graphStart = rememberSaveable { startDestination }
        var stackRoot by rememberSaveable { mutableStateOf(graphStart) }
        val navController = rememberNavController()
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route
        val tabRoutes = tabRoutesFor(layoutMode).toSet()

        capture.navController = navController
        capture.currentEntry = navBackStackEntry
        capture.stackRoot = stackRoot
        capture.backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

        // @spec NAV-012
        LaunchedEffect(externalPdfRequestId) {
            val uri = externalPdfUri ?: return@LaunchedEffect
            if (handledExternalRequestId.value == externalPdfRequestId) return@LaunchedEffect
            handledExternalRequestId.value = externalPdfRequestId
            navController.navigate("viewer/$uri") { launchSingleTop = true }
        }

        Column {
            if (currentRoute in tabRoutes) {
                tabRoutesFor(layoutMode).forEach { route ->
                    Text(
                        text = "tab:$route",
                        modifier = Modifier
                            .testTag("tab:$route")
                            .clickable {
                                // @spec NAV-014
                                navController.navigate(route) { barNavOptions(stackRoot) }
                            },
                    )
                }
            }
            NavHost(navController = navController, startDestination = graphStart) {
                composable(Screen.Search.route) { Text("s", Modifier.testTag("screen:search")) }
                composable(Screen.Library.route) { Text("l", Modifier.testTag("screen:library")) }
                composable(Screen.Documents.route) { Text("d", Modifier.testTag("screen:documents")) }
                composable(Screen.Settings.route) { Text("g", Modifier.testTag("screen:settings")) }
                composable("viewer/{uri}") { Text("v", Modifier.testTag("screen:viewer")) }
            }
        }

        // After the NavHost, mirroring LumenNavGraph: the dispatcher is LIFO
        // and the guard must register after the NavController's own pop
        // callback to win while enabled. The production exit branch calls
        // activity.finish(); the harness records it instead.
        // @spec NAV-015
        val backTarget = backGuardTarget(currentRoute, layoutMode, stackRoot)
        BackHandler(enabled = backTarget != null) {
            val target = backTarget ?: return@BackHandler
            if (currentRoute == target) {
                capture.exitRequested = true
            } else {
                navController.navigate(target) { barNavOptions(stackRoot) }
            }
        }
    }

    private class HarnessHandle(initial: NavLayoutMode) {
        val mode: MutableState<NavLayoutMode> = mutableStateOf(initial)
        val capture = Capture()
        val externalUri: MutableState<String?> = mutableStateOf(null)
        val externalRequestId: MutableState<Long> = mutableStateOf(0L)
    }

    private fun setHarness(initial: NavLayoutMode): HarnessHandle {
        val handle = HarnessHandle(initial)
        rule.setContent {
            val m = handle.mode.value
            Harness(
                layoutMode = m,
                startDestination = startDestinationFor(onboardingDone = true, layout = m),
                capture = handle.capture,
                externalPdfUri = handle.externalUri.value,
                externalPdfRequestId = handle.externalRequestId.value,
            )
        }
        return handle
    }

    // The switch itself navigates nowhere: the Settings entry survives as the
    // SAME back-stack entry (same state, same ViewModels) and no frame shows
    // another destination.
    // @spec NAV-004
    @Test
    fun switchThreeToTwo_settingsStaysComposed_onTheSameEntry() {
        val h = setHarness(NavLayoutMode.THREE_TAB)
        rule.onNodeWithTag("tab:settings").performClick()
        rule.waitForIdle()
        val entryBefore = h.capture.currentEntry
        assertEquals(Screen.Settings.route, entryBefore?.destination?.route)

        rule.runOnIdle { h.mode.value = NavLayoutMode.TWO_TAB }
        rule.waitForIdle()

        rule.onNodeWithTag("screen:settings").assertExists()
        rule.onNodeWithTag("screen:documents").assertDoesNotExist()
        rule.onNodeWithTag("tab:documents").assertExists()
        assertSame(entryBefore, h.capture.currentEntry)
    }

    // The root is never replaced: home stacks above it with its state
    // restored, and the stale root stays hidden beneath.
    // @spec NAV-014
    @Test
    fun afterSwitch_homeTabTap_opensHome_withoutTouchingTheRoot() {
        val h = setHarness(NavLayoutMode.THREE_TAB)
        rule.onNodeWithTag("tab:settings").performClick()
        rule.runOnIdle { h.mode.value = NavLayoutMode.TWO_TAB }
        rule.waitForIdle()

        rule.onNodeWithTag("tab:documents").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("screen:documents").assertExists()
        assertEquals(Screen.Search.route, h.capture.stackRoot)
    }

    // The white-page regression: the very first tap after the switch, with no
    // settling time, must land on the home screen — the inclusive root pop
    // this exercised before wedged navigation-compose's transition on device.
    // @spec NAV-014
    @Test
    fun switchThenImmediateHomeTap_showsHome() {
        val h = setHarness(NavLayoutMode.THREE_TAB)
        rule.onNodeWithTag("tab:settings").performClick()
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { h.mode.value = NavLayoutMode.TWO_TAB }
        // Tap on the very first frame the swapped bar exists.
        var frames = 0
        while (rule.onAllNodesWithTag("tab:documents").fetchSemanticsNodes().isEmpty() && frames < 10) {
            rule.mainClock.advanceTimeByFrame()
            frames++
        }
        rule.onNodeWithTag("tab:documents").performClick()
        rule.mainClock.advanceTimeBy(10_000)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()

        rule.onNodeWithTag("screen:documents").assertExists()
    }

    // @spec NAV-015
    @Test
    fun afterSwitch_backFromSettings_landsOnNewHome_neverTheStaleRoot() {
        val h = setHarness(NavLayoutMode.THREE_TAB)
        rule.onNodeWithTag("tab:settings").performClick()
        rule.runOnIdle { h.mode.value = NavLayoutMode.TWO_TAB }
        rule.waitForIdle()

        rule.runOnIdle { h.capture.backDispatcher!!.onBackPressed() }
        rule.waitForIdle()

        rule.onNodeWithTag("screen:documents").assertExists()
        rule.onNodeWithTag("screen:search").assertDoesNotExist()
        assertEquals(Screen.Search.route, h.capture.stackRoot)
    }

    // @spec NAV-015
    @Test
    fun afterSwitch_backOnHomeAboveStaleRoot_requestsExit() {
        val h = setHarness(NavLayoutMode.THREE_TAB)
        rule.onNodeWithTag("tab:settings").performClick()
        rule.runOnIdle { h.mode.value = NavLayoutMode.TWO_TAB }
        rule.waitForIdle()
        rule.onNodeWithTag("tab:documents").performClick()
        rule.waitForIdle()

        rule.runOnIdle { h.capture.backDispatcher!!.onBackPressed() }
        rule.waitForIdle()

        assertEquals(true, h.capture.exitRequested)
        rule.onNodeWithTag("screen:documents").assertExists()
        rule.onNodeWithTag("screen:search").assertDoesNotExist()
    }

    // A non-home tap above a stale root navigates normally; the home tap that
    // follows still re-roots.
    // @spec NAV-014
    @Test
    fun afterSwitch_settingsReTapThenHomeTap_bothWork() {
        val h = setHarness(NavLayoutMode.THREE_TAB)
        rule.onNodeWithTag("tab:settings").performClick()
        rule.runOnIdle { h.mode.value = NavLayoutMode.TWO_TAB }
        rule.waitForIdle()

        rule.onNodeWithTag("tab:settings").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("screen:settings").assertExists()

        rule.onNodeWithTag("tab:documents").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("screen:documents").assertExists()
        assertEquals(Screen.Search.route, h.capture.stackRoot)
    }

    // @spec NAV-004
    @Test
    fun roundTrip_threeTwoThree_settingsEntrySurvivesBothSwitches_tabsWork() {
        val h = setHarness(NavLayoutMode.THREE_TAB)
        rule.onNodeWithTag("tab:settings").performClick()
        rule.waitForIdle()
        val entryBefore = h.capture.currentEntry

        rule.runOnIdle { h.mode.value = NavLayoutMode.TWO_TAB }
        rule.waitForIdle()
        rule.runOnIdle { h.mode.value = NavLayoutMode.THREE_TAB }
        rule.waitForIdle()

        rule.onNodeWithTag("screen:settings").assertExists()
        assertSame(entryBefore, h.capture.currentEntry)

        rule.onNodeWithTag("tab:search").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("screen:search").assertExists()
    }

    // A layout switch never replays an already-handled external delivery, and
    // a re-send under a fresh request id reopens the viewer.
    // @spec NAV-012
    @Test
    fun externalDelivery_notReplayedBySwitch_reopenedByFreshRequestId() {
        val h = setHarness(NavLayoutMode.THREE_TAB)
        rule.runOnIdle {
            h.externalUri.value = "doc1"
            h.externalRequestId.value = 1L
        }
        rule.waitForIdle()
        rule.onNodeWithTag("screen:viewer").assertExists()

        rule.runOnIdle { h.capture.navController!!.popBackStack() }
        rule.waitForIdle()
        rule.onNodeWithTag("screen:viewer").assertDoesNotExist()

        rule.runOnIdle { h.mode.value = NavLayoutMode.TWO_TAB }
        rule.waitForIdle()
        rule.onNodeWithTag("screen:viewer").assertDoesNotExist()

        rule.runOnIdle { h.externalRequestId.value = 2L }
        rule.waitForIdle()
        rule.onNodeWithTag("screen:viewer").assertExists()
    }
}
