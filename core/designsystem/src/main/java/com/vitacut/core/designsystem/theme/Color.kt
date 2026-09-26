package com.vitacut.core.designsystem.theme

import androidx.compose.ui.graphics.Color

/**
 * Vita Cut palette — dark-first, "premium studio" feel.
 *
 * Original design language: near-black canvas so media is the brightest thing on screen, an
 * aurora-violet primary for creation actions, and an electric-cyan secondary for playback /
 * timeline affordances. Nothing here is copied from any existing editor's brand.
 */
internal object VitaColors {
    // Dark scheme (the default).
    val BackgroundDark = Color(0xFF0B0B0F)
    val SurfaceDark = Color(0xFF141419)
    val SurfaceVariantDark = Color(0xFF1F1F28)
    val SurfaceContainerDark = Color(0xFF181820)
    val OutlineDark = Color(0xFF2E2E3A)
    val OnSurfaceDark = Color(0xFFE8E8F0)
    val OnSurfaceVariantDark = Color(0xFFA9A9BC)

    val PrimaryDark = Color(0xFF8B5CF6) // aurora violet
    val OnPrimaryDark = Color(0xFFFFFFFF)
    val PrimaryContainerDark = Color(0xFF3B2A63)
    val SecondaryDark = Color(0xFF22D3EE) // electric cyan
    val OnSecondaryDark = Color(0xFF04222A)
    val SecondaryContainerDark = Color(0xFF123A44)
    val TertiaryDark = Color(0xFFF472B6) // accent pink (premium badges, record)
    val ErrorDark = Color(0xFFFF6B6B)
    val OnErrorDark = Color(0xFF2B0707)

    // Light scheme (opt-in via settings).
    val BackgroundLight = Color(0xFFFBFAFF)
    val SurfaceLight = Color(0xFFFFFFFF)
    val SurfaceVariantLight = Color(0xFFECEAF4)
    val SurfaceContainerLight = Color(0xFFF3F1FA)
    val OutlineLight = Color(0xFFCFCBDE)
    val OnSurfaceLight = Color(0xFF1A1A22)
    val OnSurfaceVariantLight = Color(0xFF4C4A58)

    val PrimaryLight = Color(0xFF6D3DEB)
    val OnPrimaryLight = Color(0xFFFFFFFF)
    val PrimaryContainerLight = Color(0xFFE9DEFF)
    val SecondaryLight = Color(0xFF0E7C90)
    val OnSecondaryLight = Color(0xFFFFFFFF)
    val SecondaryContainerLight = Color(0xFFC7F1FA)
    val TertiaryLight = Color(0xFFB63E82)
    val ErrorLight = Color(0xFFB3261E)
    val OnErrorLight = Color(0xFFFFFFFF)

    // Shared semantic colors used by custom components (timeline, waveform, record).
    val Waveform = Color(0xFF5EEAD4)
    val WaveformPlayed = Color(0xFF8B5CF6)
    val Playhead = Color(0xFF22D3EE)
    val RecordRed = Color(0xFFEF4444)
    val Scrim = Color(0xB3000000)
}
