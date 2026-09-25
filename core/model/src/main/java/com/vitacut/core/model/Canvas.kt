package com.vitacut.core.model

import kotlinx.serialization.Serializable

/** Canvas aspect ratios offered in the Canvas panel. */
@Serializable
enum class AspectRatio(val ratio: Float) {
    RATIO_16_9(16f / 9f),
    RATIO_9_16(9f / 16f),
    RATIO_1_1(1f),
    RATIO_4_5(4f / 5f),
    RATIO_3_4(3f / 4f),
    RATIO_21_9(21f / 9f),
    CUSTOM(0f),
}

/** Background painted behind clips that don't cover the canvas. */
@Serializable
sealed interface CanvasBackground {
    @Serializable
    data object Black : CanvasBackground

    /** ARGB solid color. */
    @Serializable
    data class Color(val argb: Int) : CanvasBackground

    /** Two-stop ARGB gradient, top→bottom. */
    @Serializable
    data class Gradient(val startArgb: Int, val endArgb: Int) : CanvasBackground

    /** Blurred, scaled-up copy of the playing clip (classic vertical-video look). */
    @Serializable
    data object BlurFill : CanvasBackground

    /** Still image (content:// or file:// URI), fitted with COVER. */
    @Serializable
    data class Image(val uri: String) : CanvasBackground
}

/**
 * Output canvas definition. Width/height are the *authoring* resolution; export scales this to
 * the chosen export resolution while preserving the aspect.
 */
@Serializable
data class CanvasSettings(
    val aspectRatio: AspectRatio = AspectRatio.RATIO_16_9,
    /** Only used when [aspectRatio] == CUSTOM. */
    val customWidth: Int = 1920,
    val customHeight: Int = 1080,
    val background: CanvasBackground = CanvasBackground.Black,
) {
    val width: Int
        get() = if (aspectRatio == AspectRatio.CUSTOM) customWidth else baseSize().first

    val height: Int
        get() = if (aspectRatio == AspectRatio.CUSTOM) customHeight else baseSize().second

    val aspect: Float
        get() = if (aspectRatio == AspectRatio.CUSTOM) {
            customWidth.toFloat() / customHeight.coerceAtLeast(1)
        } else {
            aspectRatio.ratio
        }

    /** Canonical 1080-class base resolution for the aspect. */
    private fun baseSize(): Pair<Int, Int> = when (aspectRatio) {
        AspectRatio.RATIO_16_9 -> 1920 to 1080
        AspectRatio.RATIO_9_16 -> 1080 to 1920
        AspectRatio.RATIO_1_1 -> 1080 to 1080
        AspectRatio.RATIO_4_5 -> 1080 to 1350
        AspectRatio.RATIO_3_4 -> 1080 to 1440
        AspectRatio.RATIO_21_9 -> 2560 to 1080
        AspectRatio.CUSTOM -> customWidth to customHeight
    }

    companion object {
        val DEFAULT = CanvasSettings()
    }
}
