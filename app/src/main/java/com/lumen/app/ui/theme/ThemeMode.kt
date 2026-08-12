package com.lumen.app.ui.theme

/** The Appearance theme choice. LIGHT is the default: the app has always been
 *  light, and an update should not flip a user's app without asking. */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    companion object {
        /** Parses the persisted preference; unknown or missing values fall
         *  back to LIGHT. */
        // @spec SET-APPEAR-002
        fun fromPref(value: String?): ThemeMode =
            entries.firstOrNull { it.name == value } ?: LIGHT
    }
}

/** Resolves the setting to the dark/light value every chrome surface consumes
 *  (via LumenTheme's CompositionLocal). Only the SYSTEM option consults the
 *  system theme. */
// @spec SET-APPEAR-003
fun resolveDarkTheme(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> systemDark
}
