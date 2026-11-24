package com.amatsagu.reson.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.graphics.Color
import com.amatsagu.reson.data.local.ThemeColor

private val MintDarkColorScheme = darkColorScheme(
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
    surfaceContainerHighest = ResonPillContainer,
)

private val OceanDarkColorScheme = darkColorScheme(
    primary = Color(0xFF90CAF9),
    onPrimary = Color(0xFF0D141A),
    primaryContainer = Color(0xFF1E3A5F),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFF42A5F5),
    onSecondary = Color(0xFF0D141A),
    secondaryContainer = Color(0xFF1A2A3A),
    onSecondaryContainer = ResonTextPrimary,
    background = Color(0xFF0D141A),
    onBackground = ResonTextPrimary,
    surface = Color(0xFF111921),
    onSurface = ResonTextPrimary,
    surfaceVariant = Color(0xFF17222B),
    onSurfaceVariant = Color(0xFF8B9DAE),
    outline = Color(0xFF283A4A),
    surfaceContainer = Color(0xFF17222B),
    surfaceContainerHigh = Color(0xFF1D2B37),
    surfaceContainerHighest = Color(0xFF243644),
)

private val VioletDarkColorScheme = darkColorScheme(
    primary = Color(0xFFCE93D8),
    onPrimary = Color(0xFF140E19),
    primaryContainer = Color(0xFF421E4E),
    onPrimaryContainer = Color(0xFFF3D8FF),
    secondary = Color(0xFFAB47BC),
    onSecondary = Color(0xFF140E19),
    secondaryContainer = Color(0xFF2B1D34),
    onSecondaryContainer = ResonTextPrimary,
    background = Color(0xFF140E19),
    onBackground = ResonTextPrimary,
    surface = Color(0xFF1A1321),
    onSurface = ResonTextPrimary,
    surfaceVariant = Color(0xFF221A2B),
    onSurfaceVariant = Color(0xFF9D8BAE),
    outline = Color(0xFF3B2A4A),
    surfaceContainer = Color(0xFF221A2B),
    surfaceContainerHigh = Color(0xFF2B2036),
    surfaceContainerHighest = Color(0xFF362844),
)

private val AmberDarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFE082),
    onPrimary = Color(0xFF16130D),
    primaryContainer = Color(0xFF4E3812),
    onPrimaryContainer = Color(0xFFFFECCC),
    secondary = Color(0xFFFFA726),
    onSecondary = Color(0xFF16130D),
    secondaryContainer = Color(0xFF332717),
    onSecondaryContainer = ResonTextPrimary,
    background = Color(0xFF16130D),
    onBackground = ResonTextPrimary,
    surface = Color(0xFF1D1912),
    onSurface = ResonTextPrimary,
    surfaceVariant = Color(0xFF272118),
    onSurfaceVariant = Color(0xFFA69B89),
    outline = Color(0xFF473A27),
    surfaceContainer = Color(0xFF272118),
    surfaceContainerHigh = Color(0xFF322A1F),
    surfaceContainerHighest = Color(0xFF3E3527),
)

private val RoseDarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFAB91),
    onPrimary = Color(0xFF170F0F),
    primaryContainer = Color(0xFF4E201E),
    onPrimaryContainer = Color(0xFFFFDAD4),
    secondary = Color(0xFFFF7043),
    onSecondary = Color(0xFF170F0F),
    secondaryContainer = Color(0xFF331E1E),
    onSecondaryContainer = ResonTextPrimary,
    background = Color(0xFF170F0F),
    onBackground = ResonTextPrimary,
    surface = Color(0xFF1E1414),
    onSurface = ResonTextPrimary,
    surfaceVariant = Color(0xFF281A1A),
    onSurfaceVariant = Color(0xFFA88D8D),
    outline = Color(0xFF4A2B2B),
    surfaceContainer = Color(0xFF281A1A),
    surfaceContainerHigh = Color(0xFF332222),
    surfaceContainerHighest = Color(0xFF402B2B),
)

private val TealDarkColorScheme = darkColorScheme(
    primary = Color(0xFF80CBC4),
    onPrimary = Color(0xFF0D1515),
    primaryContainer = Color(0xFF153F3C),
    onPrimaryContainer = Color(0xFFBCEEE9),
    secondary = Color(0xFF26A69A),
    onSecondary = Color(0xFF0D1515),
    secondaryContainer = Color(0xFF1A2E2D),
    onSecondaryContainer = ResonTextPrimary,
    background = Color(0xFF0D1515),
    onBackground = ResonTextPrimary,
    surface = Color(0xFF111B1B),
    onSurface = ResonTextPrimary,
    surfaceVariant = Color(0xFF172424),
    onSurfaceVariant = Color(0xFF8BA6A4),
    outline = Color(0xFF294240),
    surfaceContainer = Color(0xFF172424),
    surfaceContainerHigh = Color(0xFF1E2E2E),
    surfaceContainerHighest = Color(0xFF263A3A),
)

private val ResonLightColorScheme = lightColorScheme(
    primary = ResonProgressBarIndicator,
    onPrimary = ResonTextPrimary,
    primaryContainer = ResonPrimaryMint,
    onPrimaryContainer = ResonBackground
)

@Composable
fun ResonTheme(
    themeColor: ThemeColor = ThemeColor.System,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when (themeColor) {
        ThemeColor.System -> {
            try {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } catch (_: Throwable) {
                if (darkTheme) MintDarkColorScheme else ResonLightColorScheme
            }
        }
        ThemeColor.Mint -> MintDarkColorScheme
        ThemeColor.Ocean -> OceanDarkColorScheme
        ThemeColor.Violet -> VioletDarkColorScheme
        ThemeColor.Amber -> AmberDarkColorScheme
        ThemeColor.Rose -> RoseDarkColorScheme
        ThemeColor.Teal -> TealDarkColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
