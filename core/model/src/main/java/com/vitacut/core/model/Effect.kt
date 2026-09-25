package com.vitacut.core.model

import kotlinx.serialization.Serializable

/** Panel categories shown in the Effects sheet. */
@Serializable
enum class EffectCategory {
    TRENDING,
    CINEMATIC,
    DISTORTION,
    RETRO,
    LIGHT,
    STYLIZE,
    MOTION,
    NATURE,
}

/**
 * Every video effect the renderer can produce. Each kind maps to a concrete GLSL program (or a
 * composition of Media3 built-in effects) in the rendering module — adding a new effect means
 * adding a kind here plus a shader, nothing else.
 */
@Serializable
enum class EffectKind(val category: EffectCategory) {
    // Trending
    GLITCH(EffectCategory.TRENDING),
    VHS(EffectCategory.TRENDING),
    RGB_SPLIT(EffectCategory.TRENDING),
    SHAKE(EffectCategory.TRENDING),
    FLASH(EffectCategory.TRENDING),
    BLUR(EffectCategory.TRENDING),
    MOTION_BLUR(EffectCategory.TRENDING),

    // Cinematic
    FILM_GRAIN(EffectCategory.CINEMATIC),
    CINEMATIC_BLUR(EffectCategory.CINEMATIC),
    LENS_FLARE(EffectCategory.CINEMATIC),
    LIGHT_LEAK(EffectCategory.CINEMATIC),
    FILM_BURN(EffectCategory.CINEMATIC),

    // Distortion
    WAVE(EffectCategory.DISTORTION),
    RIPPLE(EffectCategory.DISTORTION),
    WARP(EffectCategory.DISTORTION),
    FISHEYE(EffectCategory.DISTORTION),
    MIRROR(EffectCategory.DISTORTION),
    KALEIDOSCOPE(EffectCategory.DISTORTION),

    // Retro
    CRT(EffectCategory.RETRO),
    OLD_FILM(EffectCategory.RETRO),
    DUST(EffectCategory.RETRO),
    SCRATCHES(EffectCategory.RETRO),

    // Light
    BLOOM(EffectCategory.LIGHT),
    NEON_GLOW(EffectCategory.LIGHT),
    CHROMATIC(EffectCategory.LIGHT),
    GOD_RAYS(EffectCategory.LIGHT),

    // Stylize
    PIXELATE(EffectCategory.STYLIZE),
    POSTERIZE(EffectCategory.STYLIZE),
    HALFTONE(EffectCategory.STYLIZE),
    DUOTONE(EffectCategory.STYLIZE),
    INVERT(EffectCategory.STYLIZE),
    THERMAL(EffectCategory.STYLIZE),
    NIGHT_VISION(EffectCategory.STYLIZE),
    EDGE_GLOW(EffectCategory.STYLIZE),
    OIL_PAINT(EffectCategory.STYLIZE),

    // Motion
    ZOOM_PULSE(EffectCategory.MOTION),
    SPIN_BLUR(EffectCategory.MOTION),

    // Nature
    RAIN(EffectCategory.NATURE),
    SNOW(EffectCategory.NATURE),
    FOG(EffectCategory.NATURE),
}

/**
 * An effect instance attached to a clip. Intensity is 0..1 and can be keyframed through
 * [KeyframeProperty.EFFECT_INTENSITY] in the clip's [KeyframeSet] (addressed by [id]).
 */
@Serializable
data class EffectInstance(
    val id: String = newId(),
    val kind: EffectKind,
    val intensity: Float = 1f,
    val enabled: Boolean = true,
)

/** A per-effect intensity keyframe track lives inside the clip keyframe set keyed by property+index. */
@Serializable
data class EffectKeyframes(
    val effectId: String,
    val keyframes: List<Keyframe> = emptyList(),
)
