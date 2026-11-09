package io.github.zeperus.openpad.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * openPad++ colour schemes, derived from the app icon: a yellow sticky note with
 * blue plus signs. Blue is the primary colour, the soft yellow is the secondary
 * container, and the neutrals are warm, paper-like tones.
 *
 * Every Material 3 role is set on purpose so that no purple-tinted default
 * shows through. The error roles are left at Material's defaults.
 */
internal val LightColors = lightColorScheme(
    primary = Color(0xFF0B57D0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD7E3FF),
    onPrimaryContainer = Color(0xFF001B3F),
    secondary = Color(0xFF6D5E0F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF8E287),
    onSecondaryContainer = Color(0xFF221B00),
    tertiary = Color(0xFF805600),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDDB0),
    onTertiaryContainer = Color(0xFF281800),
    background = Color(0xFFFDFCF7),
    onBackground = Color(0xFF1C1B17),
    surface = Color(0xFFFDFCF7),
    onSurface = Color(0xFF1C1B17),
    surfaceVariant = Color(0xFFE9E2D0),
    onSurfaceVariant = Color(0xFF4B4739),
    outline = Color(0xFF7C7767),
    outlineVariant = Color(0xFFCDC6B4),
    inverseSurface = Color(0xFF31302B),
    inverseOnSurface = Color(0xFFF5F1E6),
    inversePrimary = Color(0xFFABC7FF),
    surfaceTint = Color(0xFF0B57D0),
    scrim = Color(0xFF000000),
    surfaceDim = Color(0xFFDEDACD),
    surfaceBright = Color(0xFFFDFCF7),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F5EA),
    surfaceContainer = Color(0xFFF2EFE4),
    surfaceContainerHigh = Color(0xFFECE9DE),
    surfaceContainerHighest = Color(0xFFE6E3D9),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFABC7FF),
    onPrimary = Color(0xFF002F66),
    primaryContainer = Color(0xFF004490),
    onPrimaryContainer = Color(0xFFD7E3FF),
    secondary = Color(0xFFDBC66E),
    onSecondary = Color(0xFF3A3000),
    secondaryContainer = Color(0xFF534600),
    onSecondaryContainer = Color(0xFFF8E287),
    tertiary = Color(0xFFFFBA4B),
    onTertiary = Color(0xFF442C00),
    tertiaryContainer = Color(0xFF614000),
    onTertiaryContainer = Color(0xFFFFDDB0),
    background = Color(0xFF14130F),
    onBackground = Color(0xFFE6E2D8),
    surface = Color(0xFF14130F),
    onSurface = Color(0xFFE6E2D8),
    surfaceVariant = Color(0xFF4B4739),
    onSurfaceVariant = Color(0xFFCDC6B4),
    outline = Color(0xFF969080),
    outlineVariant = Color(0xFF4B4739),
    inverseSurface = Color(0xFFE6E2D8),
    inverseOnSurface = Color(0xFF31302B),
    inversePrimary = Color(0xFF0B57D0),
    surfaceTint = Color(0xFFABC7FF),
    scrim = Color(0xFF000000),
    surfaceDim = Color(0xFF14130F),
    surfaceBright = Color(0xFF3B3934),
    surfaceContainerLowest = Color(0xFF0F0E0A),
    surfaceContainerLow = Color(0xFF1C1B17),
    surfaceContainer = Color(0xFF201F1B),
    surfaceContainerHigh = Color(0xFF2B2A25),
    surfaceContainerHighest = Color(0xFF36352F),
)
