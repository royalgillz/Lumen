package com.lumen.app.ui.navigation

import androidx.navigation.NavOptionsBuilder

/** The bottom-bar layout choice. THREE_TAB is the default: an update relocates
 *  nothing without consent; the merged layout is opt-in. */
enum class NavLayoutMode {
    THREE_TAB,
    TWO_TAB;

    companion object {
        /** Parses the persisted preference; unknown or missing values fall
         *  back to THREE_TAB. */
        // @spec SET-APPEAR-002
        fun fromPref(value: String?): NavLayoutMode =
            entries.firstOrNull { it.name == value } ?: THREE_TAB
    }
}

/** Routes shown in the bottom bar for [layout]. */
// @spec NAV-001
fun tabRoutesFor(layout: NavLayoutMode): List<String> = when (layout) {
    NavLayoutMode.THREE_TAB -> listOf(Screen.Search.route, Screen.Library.route, Screen.Settings.route)
    NavLayoutMode.TWO_TAB -> listOf(Screen.Documents.route, Screen.Settings.route)
}

/** The mode's home tab — the route the back stack should be rooted at. */
// @spec NAV-014
fun modeHomeFor(layout: NavLayoutMode): String = when (layout) {
    NavLayoutMode.THREE_TAB -> Screen.Search.route
    NavLayoutMode.TWO_TAB -> Screen.Documents.route
}

/** Start destination: onboarding wins on first run; otherwise the mode's home. */
// @spec NAV-003
fun startDestinationFor(onboardingDone: Boolean, layout: NavLayoutMode): String =
    if (!onboardingDone) Screen.Onboarding.route else modeHomeFor(layout)

/**
 * Where an intercepted system back press must land, or null when the system
 * may handle it. Back is intercepted only on bottom-bar destinations sitting
 * above a stale root (the mode changed since the stack was rooted), so a
 * bar-less route is never revealed; bar-less destinations (viewer,
 * onboarding) keep their own back handling. When the returned target IS the
 * current route (back on the mode home), the caller exits the app instead of
 * navigating.
 */
// @spec NAV-015
fun backGuardTarget(currentRoute: String?, layout: NavLayoutMode, stackRoot: String): String? {
    val home = modeHomeFor(layout)
    if (stackRoot == home) return null
    if (currentRoute == null || currentRoute !in tabRoutesFor(layout)) return null
    return home
}

/**
 * The one navigation shape every bottom-bar action uses — tab taps and the
 * back-guard retarget. Pops to (never through) [popRoot], saving popped state
 * and restoring the target's. Two shapes are forbidden in the bar machinery,
 * both wedging navigation-compose 2.9.0's transitions: bare pushes (the pop
 * of a bare-pushed entry never completes — dead tabs) and inclusive root pops
 * (the transition can stall on device — blank screen until the next
 * navigation).
 */
// @spec NAV-014
fun NavOptionsBuilder.barNavOptions(popRoot: String) {
    popUpTo(popRoot) {
        saveState = true
    }
    launchSingleTop = true
    restoreState = true
}
