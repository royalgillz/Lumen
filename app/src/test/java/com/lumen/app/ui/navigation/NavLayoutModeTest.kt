package com.lumen.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class NavLayoutModeTest {

    // @spec SET-APPEAR-002
    @Test
    fun fromPref_defaultsToThreeTab() {
        assertEquals(NavLayoutMode.THREE_TAB, NavLayoutMode.fromPref(null))
        assertEquals(NavLayoutMode.THREE_TAB, NavLayoutMode.fromPref(""))
        assertEquals(NavLayoutMode.THREE_TAB, NavLayoutMode.fromPref("garbage"))
        assertEquals(NavLayoutMode.TWO_TAB, NavLayoutMode.fromPref("TWO_TAB"))
    }

    // @spec NAV-001
    @Test
    fun tabRoutes_perMode() {
        assertEquals(listOf("search", "library", "settings"), tabRoutesFor(NavLayoutMode.THREE_TAB))
        assertEquals(listOf("documents", "settings"), tabRoutesFor(NavLayoutMode.TWO_TAB))
    }

    // @spec NAV-003
    @Test
    fun startDestination_onboardingWins() {
        assertEquals("onboarding", startDestinationFor(onboardingDone = false, layout = NavLayoutMode.THREE_TAB))
        assertEquals("onboarding", startDestinationFor(onboardingDone = false, layout = NavLayoutMode.TWO_TAB))
    }

    // @spec NAV-003
    @Test
    fun startDestination_perMode() {
        assertEquals("search", startDestinationFor(onboardingDone = true, layout = NavLayoutMode.THREE_TAB))
        assertEquals("documents", startDestinationFor(onboardingDone = true, layout = NavLayoutMode.TWO_TAB))
    }
}
