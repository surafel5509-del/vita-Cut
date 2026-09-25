package com.vitacut.core.model

import kotlinx.serialization.Serializable

/** What replaces the keyed-out region. */
@Serializable
sealed interface KeyBackground {
    @Serializable
    data object Transparent : KeyBackground

    /** ARGB color. */
    @Serializable
    data class SolidColor(val argb: Int) : KeyBackground

    /** Image asset URI (content:// or file://). */
    @Serializable
    data class Image(val uri: String) : KeyBackground

    /** Video asset URI, looping behind the keyed clip. */
    @Serializable
    data class Video(val uri: String) : KeyBackground
}

/**
 * Green-screen (chroma key) parameters. Implemented as a single GLSL program shared by preview
 * and export, operating in YCbCr-ish similarity space with spill suppression.
 */
@Serializable
data class ChromaKeySettings(
    val enabled: Boolean = false,
    /** Packed ARGB of the key color. */
    val keyColorArgb: Int = 0xFF00B140.toInt(),
    /** Similarity of the key: 0..1 (higher removes a broader range of colors). */
    val intensity: Float = 0.4f,
    /** Softness of the keyed edge: 0..1. */
    val edge: Float = 0.1f,
    /** Additional edge feather: 0..1. */
    val feather: Float = 0f,
    /** Desaturation of green spill on kept pixels: 0..1. */
    val spillSuppression: Float = 0.5f,
    /** Darkening applied to semi-transparent edge pixels: 0..1. */
    val shadow: Float = 0f,
    val background: KeyBackground = KeyBackground.Transparent,
) {
    companion object {
        val DISABLED = ChromaKeySettings()
    }
}
