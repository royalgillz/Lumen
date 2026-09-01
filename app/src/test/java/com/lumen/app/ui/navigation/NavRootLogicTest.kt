package com.lumen.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavRootLogicTest {

    // @spec NAV-014
    @Test
    fun modeHome_isSearchInThreeTab_documentsInTwoTab() {
        assertEquals(Screen.Search.route, modeHomeFor(NavLayoutMode.THREE_TAB))
        assertEquals(Screen.Documents.route, modeHomeFor(NavLayoutMode.TWO_TAB))
    }

    // @spec NAV-015
    @Test
    fun backGuard_inactiveWhenRootIsHome() {
        assertNull(backGuardTarget(Screen.Settings.route, NavLayoutMode.THREE_TAB, stackRoot = Screen.Search.route))
        assertNull(backGuardTarget(Screen.Settings.route, NavLayoutMode.TWO_TAB, stackRoot = Screen.Documents.route))
    }

    // @spec NAV-015
    @Test
    fun backGuard_retargetsBarDestinationsAboveStaleRoot() {
        assertEquals(
            Screen.Documents.route,
            backGuardTarget(Screen.Settings.route, NavLayoutMode.TWO_TAB, stackRoot = Screen.Search.route),
        )
        assertEquals(
            Screen.Search.route,
            backGuardTarget(Screen.Settings.route, NavLayoutMode.THREE_TAB, stackRoot = Screen.Documents.route),
        )
    }

    // On the mode home itself the target equals the current route — the
    // caller reads that as "exit the app".
    // @spec NAV-015
    @Test
    fun backGuard_onHomeAboveStaleRoot_targetsTheHomeItself() {
        assertEquals(
            Screen.Documents.route,
            backGuardTarget(Screen.Documents.route, NavLayoutMode.TWO_TAB, stackRoot = Screen.Search.route),
        )
    }

    // Bar-less destinations keep their own back handling even above a stale root.
    // @spec NAV-015
    @Test
    fun backGuard_ignoresBarlessDestinationsAndNullRoute() {
        assertNull(backGuardTarget("pdf_viewer?uri={uri}", NavLayoutMode.TWO_TAB, stackRoot = Screen.Search.route))
        assertNull(backGuardTarget(Screen.Onboarding.route, NavLayoutMode.TWO_TAB, stackRoot = Screen.Search.route))
        assertNull(backGuardTarget(null, NavLayoutMode.TWO_TAB, stackRoot = Screen.Search.route))
    }
}
