package com.vitacut.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class MaskShape {
    NONE,
    RECTANGLE,
    CIRCLE,
    LINEAR,
    RADIAL,
    /** Freeform masks are stored as a normalized polygon; feasible for modest point counts. */
    FREEFORM,
}

/**
 * Mask applied to a clip's rendered output. Position/scale/rotation are in the same normalized
 * space as [SpatialTransform]; all parameters can be keyframed (MASK_* properties) for animated
 * masks.
 */
@Serializable
data class MaskSettings(
    val shape: MaskShape = MaskShape.NONE,
    /** Center of the mask relative to the clip center, in half-frame units (-1..1). */
    val centerX: Float = 0f,
    val centerY: Float = 0f,
    /** Mask size relative to frame size (1 = full frame). */
    val scaleX: Float = 0.7f,
    val scaleY: Float = 0.7f,
    val rotationDegrees: Float = 0f,
    /** Edge softness, 0 (hard) .. 1 (fully feathered). */
    val feather: Float = 0.2f,
    /** Show the masked-out region instead of the masked-in region. */
    val invert: Boolean = false,
    /** Normalized polygon points (x,y pairs in 0..1) for [MaskShape.FREEFORM]. */
    val polygon: List<Float> = emptyList(),
) {
    val isActive: Boolean get() = shape != MaskShape.NONE

    companion object {
        val NONE = MaskSettings()
    }
}
