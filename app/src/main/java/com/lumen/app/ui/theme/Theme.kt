package com.lumen.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = ForestGreen,
    onPrimary = OnPrimaryLight,
    // Revamp: darker toolbar/background container for stronger contrast in light mode.
    primaryContainer = ForestGreen,
    onPrimaryContainer = Color.White,
    secondary = Terracotta,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF6D9CC),
    onSecondaryContainer = Color(0xFF3A1505),
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

private val DarkColorScheme = darkColorScheme(
    primary = ForestGreenDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = Color(0xFF004D28),
    onPrimaryContainer = Color(0xFFB8DFBF),
    secondary = TerracottaDark,
    onSecondary = Color(0xFF5A2410),
    secondaryContainer = Color(0xFF5A2E1C),
    onSecondaryContainer = Color(0xFFF6D9CC),
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

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
