package com.vitacut.core.model

import kotlinx.serialization.Serializable

/**
 * Spatial transform shared by every visual timeline element (video clips, text, stickers,
 * overlays).
 *
 * Coordinate space: normalized device coordinates relative to the canvas, origin at the center,
 * x in [-1, 1] left→right, y in [-1, 1] **bottom→top** (OpenGL convention — the rendering layer
 * and the Compose gesture layer both convert through [com.vitacut.core.model.toCanvasY] style
 * helpers rather than inventing per-platform variants).
 *
 * Scale semantics: 1.0 = natural size (video: fitted to canvas per [contentFit]; text/sticker:
 * authored pixel size mapped to canvas fraction).
 */
@Serializable
data class SpatialTransform(
    /** Center offset from canvas center, in half-canvas units. */
    val translationX: Float = 0f,
    val translationY: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    /** Counter-clockwise rotation in degrees. */
    val rotationDegrees: Float = 0f,
    /** 0..1 */
    val opacity: Float = 1f,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
) {
    companion object {
        val DEFAULT = SpatialTransform()
    }

    val isIdentity: Boolean
        get() = this == DEFAULT
}

/** How a clip's pixels are fitted into the canvas rectangle before transform is applied. */
@Serializable
enum class ContentFit {
    /** Scale uniformly to fill the canvas, cropping overflow (default for full-frame video). */
    COVER,

    /** Scale uniformly to fit inside the canvas, letterboxing. */
    FIT,

    /** Stretch to exactly fill the canvas (ignores aspect). */
    FILL,
}

/**
 * A user crop applied to the source frame, in normalized source coordinates (0..1 from the
 * top-left of the *rotated* source frame).
 */
@Serializable
data class CropSettings(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    val isFullFrame: Boolean get() = left <= 0f && top <= 0f && right >= 1f && bottom >= 1f

    val widthFraction: Float get() = (right - left).coerceIn(0.01f, 1f)
    val heightFraction: Float get() = (bottom - top).coerceIn(0.01f, 1f)

    companion object {
        val FULL = CropSettings()
    }
}
