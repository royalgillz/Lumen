package com.lumen.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Selected-state containers (segmented rows, filter chips, chip-styled pills)
 * consume the secondaryContainer slot — it must be forest-green family in both
 * schemes, matching the bottom bar's selection indicator, never the retired
 * terracotta wash.
 */
class SelectionColorTest {

    // @spec SET-APPEAR-006
    @Test
    fun lightScheme_selectionContainerIsPaleForestGreen() {
        assertEquals(Color(0xFFD6E6D6), LightColorScheme.secondaryContainer)
        assertEquals(Color(0xFF11291A), LightColorScheme.onSecondaryContainer)
    }

    // @spec SET-APPEAR-006
    @Test
    fun darkScheme_selectionContainerIsDeepForestGreen() {
        assertEquals(Color(0xFF2F4A38), DarkColorScheme.secondaryContainer)
        assertEquals(Color(0xFFCCE5D2), DarkColorScheme.onSecondaryContainer)
    }
}
