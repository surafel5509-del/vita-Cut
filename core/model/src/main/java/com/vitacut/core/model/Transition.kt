package com.vitacut.core.model

import kotlinx.serialization.Serializable

/** Transition library categories. */
@Serializable
enum class TransitionCategory {
    BASIC,
    BLUR,
    ZOOM,
    SPIN,
    SLIDE,
    SHAKE,
    GLITCH,
    FLASH,
    LIGHT,
    THREE_D,
    CINEMATIC,
}

/** Direction where the transition supports one (slides, wipes). */
@Serializable
enum class TransitionDirection {
    NONE,
    LEFT,
    RIGHT,
    UP,
    DOWN,
}

/**
 * Transition kinds implemented as time-varying edge effects on the incoming/outgoing clip.
 *
 * Each kind renders identically in preview (via the player effect chain) and in export (via the
 * Transformer effect chain) because both consume the same shader with the same parameters.
 */
@Serializable
enum class TransitionKind(val category: TransitionCategory, val supportsDirection: Boolean = false) {
    // Basic
    FADE(TransitionCategory.BASIC),
    DISSOLVE_TO_BLACK(TransitionCategory.BASIC),
    DISSOLVE_TO_WHITE(TransitionCategory.BASIC),

    // Blur
    BLUR_IN(TransitionCategory.BLUR),
    BLUR_OUT(TransitionCategory.BLUR),

    // Zoom
    ZOOM_IN(TransitionCategory.ZOOM),
    ZOOM_OUT(TransitionCategory.ZOOM),

    // Spin
    SPIN_IN(TransitionCategory.SPIN),
    SPIN_OUT(TransitionCategory.SPIN),

    // Slide
    SLIDE(TransitionCategory.SLIDE, supportsDirection = true),
    PUSH(TransitionCategory.SLIDE, supportsDirection = true),

    // Shake
    SHAKE_IN(TransitionCategory.SHAKE),

    // Glitch
    GLITCH_IN(TransitionCategory.GLITCH),
    GLITCH_OUT(TransitionCategory.GLITCH),

    // Flash
    FLASH_IN(TransitionCategory.FLASH),
    FLASH_OUT(TransitionCategory.FLASH),

    // Light
    LIGHT_SWEEP(TransitionCategory.LIGHT),
    LIGHT_LEAK_IN(TransitionCategory.LIGHT),

    // 3D
    CUBE_SPIN(TransitionCategory.THREE_D, supportsDirection = true),
    FLIP(TransitionCategory.THREE_D, supportsDirection = true),

    // Cinematic
    WIPE(TransitionCategory.CINEMATIC, supportsDirection = true),
    CINEMATIC_BARS(TransitionCategory.CINEMATIC),
}

/**
 * A transition applied at the boundary between two adjacent clips. The renderer attaches it to
 * the *outgoing* clip's tail ([atEnd] = true) or the *incoming* clip's head.
 */
@Serializable
data class TransitionState(
    val kind: TransitionKind,
    val durationUs: Long = 500_000L,
    /** 0..1 */
    val intensity: Float = 1f,
    val direction: TransitionDirection = TransitionDirection.NONE,
    /** True when the transition plays on the clip's tail (out), false on its head (in). */
    val atEnd: Boolean = true,
)
