package com.amatsagu.reson.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val ResonDarkColorScheme = darkColorScheme(
    primary = ResonPrimaryMint,
    onPrimary = ResonBackground,
    primaryContainer = ResonPrimaryContainer,
    onPrimaryContainer = ResonOnPrimaryContainer,
    secondary = ResonProgressBarIndicator,
    onSecondary = ResonBackground,
    secondaryContainer = ResonPillContainer,
    onSecondaryContainer = ResonTextPrimary,
    background = ResonBackground,
    onBackground = ResonTextPrimary,
    surface = ResonSurface,
    onSurface = ResonTextPrimary,
    surfaceVariant = ResonCardSurface,
    onSurfaceVariant = ResonTextSecondary,
    outline = ResonPillDivider,
    surfaceContainer = ResonCardSurface,
    surfaceContainerHigh = ResonCardSurfaceVariant,
    surfaceContainerHighest = ResonPillContainer
)

private val ResonLightColorScheme = lightColorScheme(
    primary = ResonProgressBarIndicator,
    onPrimary = ResonTextPrimary,
    primaryContainer = ResonPrimaryMint,
    onPrimaryContainer = ResonBackground
)

/**
 * Reson Theme supporting Android Material You dynamic wallpaper colors (Approach 1).
 * When dynamicColor is true, colors adapt automatically to the user's system wallpaper & accent.
 */
@Composable
fun ResonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> ResonDarkColorScheme
        else -> ResonLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
