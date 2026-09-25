package com.vitacut.core.model

/**
 * Built-in text style presets for the Ultimate editor. Styles are pure data so preview, export
 * and the text panel share one definition.
 */
data class TextStylePreset(
    val id: String,
    val nameKey: String,
    val style: TextStyle,
    val inAnimation: TextAnimationIn = TextAnimationIn.NONE,
    val outAnimation: TextAnimationOut = TextAnimationOut.NONE,
    val loopAnimation: TextAnimationLoop = TextAnimationLoop.NONE,
)

object TextStyleLibrary {

    val ALL: List<TextStylePreset> = listOf(
        preset(
            id = "cinematic-gold",
            style = TextStyle(
                fontFamilyKey = "serif",
                sizeFraction = 0.08f,
                bold = true,
                colorArgb = 0xFFFFE08A.toInt(),
                letterSpacing = 0.08f,
                shadowColorArgb = 0xCC000000.toInt(),
                shadowRadiusFraction = 0.18f,
                shadowDyFraction = 0.06f,
            ),
            inAnimation = TextAnimationIn.FADE,
        ),
        preset(
            id = "neon-cyan",
            style = TextStyle(
                sizeFraction = 0.07f,
                bold = true,
                colorArgb = 0xFF67F6FF.toInt(),
                strokeColorArgb = 0xFF083344.toInt(),
                strokeWidthFraction = 0.06f,
                shadowColorArgb = 0xFF22D3EE.toInt(),
                shadowRadiusFraction = 0.35f,
            ),
            inAnimation = TextAnimationIn.NEON,
            loopAnimation = TextAnimationLoop.NEON,
        ),
        preset(
            id = "bold-outline",
            style = TextStyle(
                sizeFraction = 0.09f,
                bold = true,
                colorArgb = 0xFFFFFFFF.toInt(),
                strokeColorArgb = 0xFF000000.toInt(),
                strokeWidthFraction = 0.12f,
                letterSpacing = 0.02f,
            ),
            inAnimation = TextAnimationIn.POP,
        ),
        preset(
            id = "subtitle-box",
            style = TextStyle(
                sizeFraction = 0.045f,
                colorArgb = 0xFFFFFFFF.toInt(),
                backgroundColorArgb = 0xCC111111.toInt(),
                backgroundCornerRadiusFraction = 0.012f,
                backgroundPaddingFraction = 0.018f,
            ),
            inAnimation = TextAnimationIn.SLIDE_UP,
        ),
        preset(
            id = "retro-serif",
            style = TextStyle(
                fontFamilyKey = "serif",
                sizeFraction = 0.075f,
                italic = true,
                colorArgb = 0xFFF4E1C1.toInt(),
                shadowColorArgb = 0x99000000.toInt(),
                shadowRadiusFraction = 0.12f,
            ),
            inAnimation = TextAnimationIn.FADE,
            loopAnimation = TextAnimationLoop.FLOATING,
        ),
        preset(
            id = "typewriter-mono",
            style = TextStyle(
                fontFamilyKey = "monospace",
                sizeFraction = 0.05f,
                colorArgb = 0xFFE2E8F0.toInt(),
                letterSpacing = 0.04f,
                backgroundColorArgb = 0xE6101018.toInt(),
                backgroundPaddingFraction = 0.014f,
            ),
            inAnimation = TextAnimationIn.TYPEWRITER,
        ),
        preset(
            id = "social-pop",
            style = TextStyle(
                sizeFraction = 0.085f,
                bold = true,
                colorArgb = 0xFFFF4D8D.toInt(),
                strokeColorArgb = 0xFFFFFFFF.toInt(),
                strokeWidthFraction = 0.08f,
                shadowColorArgb = 0x66000000.toInt(),
                shadowRadiusFraction = 0.16f,
            ),
            inAnimation = TextAnimationIn.BOUNCE,
            loopAnimation = TextAnimationLoop.PULSE,
        ),
        preset(
            id = "wedding-script",
            style = TextStyle(
                fontFamilyKey = "serif",
                sizeFraction = 0.07f,
                italic = true,
                colorArgb = 0xFFFFF7ED.toInt(),
                letterSpacing = 0.06f,
                shadowColorArgb = 0x55FFFFFF.toInt(),
                shadowRadiusFraction = 0.2f,
            ),
            inAnimation = TextAnimationIn.ZOOM,
        ),
        preset(
            id = "sports-impact",
            style = TextStyle(
                sizeFraction = 0.1f,
                bold = true,
                colorArgb = 0xFFFFF200.toInt(),
                strokeColorArgb = 0xFF111111.toInt(),
                strokeWidthFraction = 0.1f,
                letterSpacing = 0.04f,
            ),
            inAnimation = TextAnimationIn.SLIDE,
            loopAnimation = TextAnimationLoop.SHAKE,
        ),
        preset(
            id = "minimal-white",
            style = TextStyle(
                sizeFraction = 0.055f,
                colorArgb = 0xFFF8FAFC.toInt(),
                letterSpacing = 0.14f,
            ),
            inAnimation = TextAnimationIn.FADE,
        ),
        preset(
            id = "karaoke-yellow",
            style = TextStyle(
                sizeFraction = 0.06f,
                bold = true,
                colorArgb = 0xFFFFF59D.toInt(),
                strokeColorArgb = 0xFF1A1300.toInt(),
                strokeWidthFraction = 0.08f,
                backgroundColorArgb = 0x99000000.toInt(),
                backgroundPaddingFraction = 0.012f,
            ),
            inAnimation = TextAnimationIn.TYPEWRITER,
            loopAnimation = TextAnimationLoop.PULSE,
        ),
        preset(
            id = "horror-red",
            style = TextStyle(
                fontFamilyKey = "serif",
                sizeFraction = 0.09f,
                bold = true,
                colorArgb = 0xFFFF2A2A.toInt(),
                shadowColorArgb = 0xFF7F0000.toInt(),
                shadowRadiusFraction = 0.28f,
            ),
            inAnimation = TextAnimationIn.GLITCH,
            loopAnimation = TextAnimationLoop.GLITCH,
        ),
        preset(
            id = "luxury-gradient",
            style = TextStyle(
                fontFamilyKey = "serif",
                sizeFraction = 0.08f,
                bold = true,
                colorArgb = 0xFFFFE7A3.toInt(),
                gradientStartArgb = 0xFFFFF3C4.toInt(),
                gradientEndArgb = 0xFFD4A017.toInt(),
                letterSpacing = 0.1f,
            ),
            inAnimation = TextAnimationIn.ZOOM,
            loopAnimation = TextAnimationLoop.FLOATING,
        ),
        preset(
            id = "sticker-bubble",
            style = TextStyle(
                sizeFraction = 0.06f,
                bold = true,
                colorArgb = 0xFF111827.toInt(),
                backgroundColorArgb = 0xFFFFFFFF.toInt(),
                backgroundCornerRadiusFraction = 0.04f,
                backgroundPaddingFraction = 0.022f,
            ),
            inAnimation = TextAnimationIn.POP,
            loopAnimation = TextAnimationLoop.BOUNCE,
        ),
        preset(
            id = "lower-third",
            style = TextStyle(
                sizeFraction = 0.042f,
                bold = true,
                alignment = TextAlignment.START,
                colorArgb = 0xFFFFFFFF.toInt(),
                backgroundColorArgb = 0xE68B5CF6.toInt(),
                backgroundCornerRadiusFraction = 0.008f,
                backgroundPaddingFraction = 0.016f,
            ),
            inAnimation = TextAnimationIn.SLIDE_UP,
        ),
        preset(
            id = "wave-title",
            style = TextStyle(
                sizeFraction = 0.08f,
                bold = true,
                colorArgb = 0xFF22D3EE.toInt(),
                letterSpacing = 0.05f,
            ),
            inAnimation = TextAnimationIn.WAVE,
            loopAnimation = TextAnimationLoop.WAVE,
        ),
    )

    private val presetsById = ALL.associateBy { it.id }

    fun byId(id: String): TextStylePreset? = presetsById[id]

    private fun preset(
        id: String,
        style: TextStyle,
        inAnimation: TextAnimationIn = TextAnimationIn.NONE,
        outAnimation: TextAnimationOut = TextAnimationOut.NONE,
        loopAnimation: TextAnimationLoop = TextAnimationLoop.NONE,
    ) = TextStylePreset(
        id = id,
        nameKey = "text_preset_$id".replace('-', '_'),
        style = style,
        inAnimation = inAnimation,
        outAnimation = outAnimation,
        loopAnimation = loopAnimation,
    )
}
