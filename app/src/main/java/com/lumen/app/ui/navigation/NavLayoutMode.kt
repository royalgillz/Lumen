package com.lumen.app.ui.navigation

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

/** Start destination: onboarding wins on first run; otherwise the mode's home. */
// @spec NAV-003
fun startDestinationFor(onboardingDone: Boolean, layout: NavLayoutMode): String = when {
    !onboardingDone -> Screen.Onboarding.route
    layout == NavLayoutMode.TWO_TAB -> Screen.Documents.route
    else -> Screen.Search.route
}
