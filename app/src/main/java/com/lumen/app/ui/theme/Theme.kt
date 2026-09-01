package com.lumen.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Internal (not private) so unit tests can pin the selection-container tones.
internal val LightColorScheme = lightColorScheme(
    primary = ForestGreen,
    onPrimary = OnPrimaryLight,
    // Revamp: darker toolbar/background container for stronger contrast in light mode.
    primaryContainer = ForestGreen,
    onPrimaryContainer = Color.White,
    secondary = Terracotta,
    onSecondary = Color.White,
    // Selection containers (segmented rows, filter chips, chip-styled pills)
    // are forest-green family, matching the nav bar's indicator — the old pale
    // terracotta read as an error state against green chrome.
    // @spec SET-APPEAR-006
    secondaryContainer = Color(0xFFD6E6D6),
    onSecondaryContainer = Color(0xFF11291A),
    tertiary = OcrTint,
    onTertiary = Color(0xFFFFFFFF),
    background = WarmWhite,
    onBackground = OnSurfaceLight,
    surface = WarmWhite,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    surfaceTint = ForestGreen,
    surfaceContainerLowest = SurfaceContainerLowestLight,
    surfaceContainerLow = SurfaceContainerLowLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    surfaceContainerHighest = SurfaceContainerHighestLight,
    error = ErrorRed,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    outline = Color(0xFF717971),
)

internal val DarkColorScheme = darkColorScheme(
    primary = ForestGreenDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = Color(0xFF004D28),
    onPrimaryContainer = Color(0xFFB8DFBF),
    secondary = TerracottaDark,
    onSecondary = Color(0xFF5A2410),
    // @spec SET-APPEAR-006
    secondaryContainer = Color(0xFF2F4A38),
    onSecondaryContainer = Color(0xFFCCE5D2),
    tertiary = OcrTintDark,
    onTertiary = Color(0xFF3E2800),
    background = DeepForestBg,
    onBackground = OnSurfaceDark,
    surface = DeepForestBg,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    surfaceTint = ForestGreenDark,
    surfaceContainerLowest = SurfaceContainerLowestDark,
    surfaceContainerLow = SurfaceContainerLowDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighestDark,
    error = ErrorRedDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    outline = Color(0xFF8B938B),
)

/**
 * The resolved dark/light value for the whole app — the Theme setting applied,
 * with SYSTEM already resolved. Chrome surfaces (viewer status-bar contrast,
 * canvas colors) read this instead of querying the system theme, so they
 * follow the user's choice.
 */
// @spec SET-APPEAR-003
val LocalLumenDarkTheme = staticCompositionLocalOf { false }

@Composable
fun LumenTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalLumenDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
