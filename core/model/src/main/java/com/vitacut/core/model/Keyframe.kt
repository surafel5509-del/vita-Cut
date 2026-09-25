package com.vitacut.core.model

import kotlinx.serialization.Serializable

/**
 * Every property that can be keyframed. One enum constant per animatable scalar so that
 * keyframe tracks serialize compactly and evaluate generically.
 *
 * Color-valued properties (e.g. [TEXT_COLOR]) store ARGB ints packed into the float value via
 * [Float.fromBits]/[toFloatBits]; helpers live in the keyframe engine.
 */
@Serializable
enum class KeyframeProperty {
    POSITION_X,
    POSITION_Y,
    SCALE_X,
    SCALE_Y,
    ROTATION,
    OPACITY,
    VOLUME,
    FILTER_INTENSITY,
    EFFECT_INTENSITY,
    BRIGHTNESS,
    EXPOSURE,
    CONTRAST,
    SATURATION,
    VIBRANCE,
    TEMPERATURE,
    TINT,
    HIGHLIGHTS,
    SHADOWS,
    WHITES,
    BLACKS,
    FADE,
    SHARPEN,
    CLARITY,
    VIGNETTE,
    GRAIN,
    BLUR,
    TEXT_SIZE,
    TEXT_COLOR,
    LETTER_SPACING,
    LINE_SPACING,
    MASK_CENTER_X,
    MASK_CENTER_Y,
    MASK_SCALE,
    MASK_ROTATION,
    MASK_FEATHER,
    CROP_LEFT,
    CROP_TOP,
    CROP_RIGHT,
    CROP_BOTTOM,
}

/** Interpolation between two keyframes. */
@Serializable
enum class Interpolation {
    LINEAR,
    EASE_IN,
    EASE_OUT,
    EASE_IN_OUT,

    /** Hold the previous value until the next keyframe. */
    HOLD,
}

/**
 * A single keyframe. [timeUs] is relative to the owning item's timeline start so keyframes move
 * with their clip.
 */
@Serializable
data class Keyframe(
    val timeUs: Long,
    val value: Float,
    /** Interpolation from *this* keyframe to the next one. */
    val interpolation: Interpolation = Interpolation.LINEAR,
)

/** All keyframes for one property, sorted by time (invariant enforced by the timeline engine). */
@Serializable
data class KeyframeTrack(
    val property: KeyframeProperty,
    val keyframes: List<Keyframe> = emptyList(),
) {
    fun valueAt(timeUs: Long): Float? = keyframes.firstOrNull()?.let { _ ->
        KeyframeMath.evaluate(keyframes, timeUs)
    }
}

/** Keyframe sets attached to a timeline item. */
@Serializable
data class KeyframeSet(
    val tracks: List<KeyframeTrack> = emptyList(),
) {
    val isEmpty: Boolean get() = tracks.all { it.keyframes.isEmpty() }

    fun trackFor(property: KeyframeProperty): KeyframeTrack? =
        tracks.firstOrNull { it.property == property }

    fun withTrack(track: KeyframeTrack): KeyframeSet =
        copy(tracks = tracks.filterNot { it.property == track.property } + track)

    fun withoutTrack(property: KeyframeProperty): KeyframeSet =
        copy(tracks = tracks.filterNot { it.property == property })

    companion object {
        val EMPTY = KeyframeSet()
    }
}
