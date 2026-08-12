package com.lumen.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {

    // @spec SET-APPEAR-002
    @Test
    fun fromPref_defaultsToLight() {
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromPref(null))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromPref(""))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromPref("garbage"))
    }

    // @spec SET-APPEAR-002
    @Test
    fun fromPref_parsesStoredValues() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromPref("SYSTEM"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromPref("LIGHT"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromPref("DARK"))
    }

    // @spec SET-APPEAR-003
    @Test
    fun explicitChoices_ignoreSystemTheme() {
        assertFalse(resolveDarkTheme(ThemeMode.LIGHT, systemDark = true))
        assertFalse(resolveDarkTheme(ThemeMode.LIGHT, systemDark = false))
        assertTrue(resolveDarkTheme(ThemeMode.DARK, systemDark = false))
        assertTrue(resolveDarkTheme(ThemeMode.DARK, systemDark = true))
    }

    // @spec SET-APPEAR-003
    @Test
    fun systemChoice_followsSystemTheme() {
        assertTrue(resolveDarkTheme(ThemeMode.SYSTEM, systemDark = true))
        assertFalse(resolveDarkTheme(ThemeMode.SYSTEM, systemDark = false))
    }
}
