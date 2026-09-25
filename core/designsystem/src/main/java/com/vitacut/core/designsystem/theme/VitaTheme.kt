package com.vitacut.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** How the app resolves dark vs light. Dark is the product default (dark-first spec). */
enum class VitaThemeMode { DARK, LIGHT, SYSTEM }

private val VitaDarkScheme = darkColorScheme(
    primary = VitaColors.PrimaryDark,
    onPrimary = VitaColors.OnPrimaryDark,
    primaryContainer = VitaColors.PrimaryContainerDark,
    onPrimaryContainer = VitaColors.OnSurfaceDark,
    secondary = VitaColors.SecondaryDark,
    onSecondary = VitaColors.OnSecondaryDark,
    secondaryContainer = VitaColors.SecondaryContainerDark,
    onSecondaryContainer = VitaColors.OnSurfaceDark,
    tertiary = VitaColors.TertiaryDark,
    background = VitaColors.BackgroundDark,
    onBackground = VitaColors.OnSurfaceDark,
    surface = VitaColors.SurfaceDark,
    onSurface = VitaColors.OnSurfaceDark,
    surfaceVariant = VitaColors.SurfaceVariantDark,
    onSurfaceVariant = VitaColors.OnSurfaceVariantDark,
    surfaceContainer = VitaColors.SurfaceContainerDark,
    outline = VitaColors.OutlineDark,
    error = VitaColors.ErrorDark,
    onError = VitaColors.OnErrorDark,
    scrim = VitaColors.Scrim,
)

private val VitaLightScheme = lightColorScheme(
    primary = VitaColors.PrimaryLight,
    onPrimary = VitaColors.OnPrimaryLight,
    primaryContainer = VitaColors.PrimaryContainerLight,
    onPrimaryContainer = VitaColors.OnSurfaceLight,
    secondary = VitaColors.SecondaryLight,
    onSecondary = VitaColors.OnSecondaryLight,
    secondaryContainer = VitaColors.SecondaryContainerLight,
    onSecondaryContainer = VitaColors.OnSurfaceLight,
    tertiary = VitaColors.TertiaryLight,
    background = VitaColors.BackgroundLight,
    onBackground = VitaColors.OnSurfaceLight,
    surface = VitaColors.SurfaceLight,
    onSurface = VitaColors.OnSurfaceLight,
    surfaceVariant = VitaColors.SurfaceVariantLight,
    onSurfaceVariant = VitaColors.OnSurfaceVariantLight,
    surfaceContainer = VitaColors.SurfaceContainerLight,
    outline = VitaColors.OutlineLight,
    error = VitaColors.ErrorLight,
    onError = VitaColors.OnErrorLight,
    scrim = VitaColors.Scrim,
)

private val VitaShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Semantic colors beyond the Material scheme, consumed by custom components (timeline,
 * waveform, record button) so features never hardcode raw colors.
 */
@Immutable
data class VitaExtendedColors(
    val waveform: Color,
    val waveformPlayed: Color,
    val playhead: Color,
    val recordRed: Color,
)

private val LocalVitaExtendedColors = staticCompositionLocalOf {
    VitaExtendedColors(
        waveform = VitaColors.Waveform,
        waveformPlayed = VitaColors.WaveformPlayed,
        playhead = VitaColors.Playhead,
        recordRed = VitaColors.RecordRed,
    )
}

object VitaTheme {
    val extended: VitaExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalVitaExtendedColors.current
}

/**
 * Root theme. Dark-first: [mode] defaults to [VitaThemeMode.DARK] — matching the product
 * identity — and the settings layer maps the user's preference onto it.
 */
@Composable
fun VitaTheme(
    mode: VitaThemeMode = VitaThemeMode.DARK,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        VitaThemeMode.DARK -> true
        VitaThemeMode.LIGHT -> false
        VitaThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) VitaDarkScheme else VitaLightScheme,
        typography = VitaTypography,
        shapes = VitaShapes,
        content = content,
    )
}
