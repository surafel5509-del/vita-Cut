package com.vitacut.core.rendering.overlay

import com.vitacut.core.model.BlendMode
import com.vitacut.core.model.SpatialTransform

/**
 * Where and how an overlay bitmap sits on the canvas, in the NDC space consumed by
 * `fragment_overlay_composite_es2.glsl`.
 *
 * This single struct is the contract between:
 * - the export compositor ([com.vitacut.core.rendering.effects.OverlayCompositeEffect]),
 * - the in-preview compositor (same effect on the player chain), and
 * - the editor's Compose gesture/preview layer, which converts it to layout coordinates via
 *   [toComposeSpace]. One math, three consumers — no preview/export drift.
 */
data class OverlayPlacement(
    /** Center x in NDC (-1..1, right positive). */
    val centerX: Float,
    /** Center y in NDC (-1..1, **up** positive — GL convention). */
    val centerY: Float,
    /** Half-width as fraction of canvas width (0.5 = spans the full canvas). */
    val halfWidth: Float,
    /** Half-height as fraction of canvas height. */
    val halfHeight: Float,
    /** Counter-clockwise rotation in degrees. */
    val rotationDegrees: Float = 0f,
    val opacity: Float = 1f,
    val blendMode: BlendMode = BlendMode.NORMAL,
) {

    /** Conversion to top-left-origin, y-down fractions of the canvas (Compose space). */
    fun toComposeSpace(): ComposePlacement = ComposePlacement(
        centerXFraction = centerX * 0.5f + 0.5f,
        centerYFraction = 0.5f - centerY * 0.5f,
        widthFraction = halfWidth * 2f,
        heightFraction = halfHeight * 2f,
        rotationDegrees = rotationDegrees,
        opacity = opacity,
    )

    companion object {
        /**
         * Builds a placement for a bitmap of [bitmapWidth]x[bitmapHeight] pixels rendered against
         * a [canvasWidth]x[canvasHeight] canvas, positioned by [transform] (whose translation is
         * in half-canvas units and whose scale multiplies the natural bitmap size).
         */
        fun fromTransform(
            transform: SpatialTransform,
            bitmapWidth: Int,
            bitmapHeight: Int,
            canvasWidth: Int,
            canvasHeight: Int,
            opacityOverride: Float? = null,
            scaleOverride: Float? = null,
            translationOverrideX: Float? = null,
            translationOverrideY: Float? = null,
            rotationOverride: Float? = null,
            blendMode: BlendMode = BlendMode.NORMAL,
        ): OverlayPlacement {
            val scale = scaleOverride ?: 1f
            return OverlayPlacement(
                centerX = translationOverrideX ?: transform.translationX,
                centerY = translationOverrideY ?: transform.translationY,
                halfWidth = bitmapWidth.toFloat() / (2f * canvasWidth) * transform.scaleX * scale,
                halfHeight = bitmapHeight.toFloat() / (2f * canvasHeight) * transform.scaleY * scale,
                rotationDegrees = rotationOverride ?: transform.rotationDegrees,
                opacity = opacityOverride ?: transform.opacity,
                blendMode = blendMode,
            )
        }
    }
}

/** Compose-friendly placement: fractions from the top-left, y-down. */
data class ComposePlacement(
    val centerXFraction: Float,
    val centerYFraction: Float,
    val widthFraction: Float,
    val heightFraction: Float,
    val rotationDegrees: Float,
    val opacity: Float,
)
