package com.vitacut.core.rendering.effects

import android.content.Context
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import com.vitacut.core.model.EffectKind
import com.vitacut.core.rendering.gl.ShaderPaths
import com.vitacut.core.rendering.gl.VitaGlShaderProgram

/**
 * Maps every [EffectKind] to its shader + per-frame uniform uploads.
 *
 * Each effect instance is time-varying: intensity (and effect-specific params like the flash
 * envelope) are supplied by lambdas evaluated with the clip-local presentation time, so
 * keyframed effect intensity animates in both preview and export.
 */
class FxEffect(
    private val kind: EffectKind,
    private val intensityAt: (timeUs: Long) -> Float,
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): BaseGlShaderProgram =
        FxProgram(context, kind, intensityAt)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false

    private class FxProgram(
        context: Context,
        private val kind: EffectKind,
        private val intensityAt: (Long) -> Float,
    ) : VitaGlShaderProgram(context, ShaderPaths.VERTEX_FULLSCREEN, shaderFor(kind)) {

        override fun onDrawFrame(presentationTimeUs: Long) {
            val timeSec = presentationTimeUs / 1_000_000f
            val intensity = intensityAt(presentationTimeUs).coerceIn(0f, 1f)
            when (kind) {
                EffectKind.GLITCH -> {
                    glProgram.setFloatUniform("uTimeSec", timeSec)
                    glProgram.setFloatUniform("uIntensity", intensity)
                }

                EffectKind.VHS -> {
                    glProgram.setFloatUniform("uTimeSec", timeSec)
                    glProgram.setFloatUniform("uIntensity", intensity)
                }

                EffectKind.RGB_SPLIT, EffectKind.CHROMATIC -> {
                    glProgram.setFloatUniform("uIntensity", intensity)
                    glProgram.setFloatUniform("uAngleDeg", if (kind == EffectKind.CHROMATIC) 35f else 0f)
                    glProgram.setFloatUniform("uTimeSec", timeSec)
                }

                EffectKind.SHAKE -> {
                    glProgram.setFloatUniform("uTimeSec", timeSec)
                    glProgram.setFloatUniform("uIntensity", intensity)
                }

                EffectKind.FLASH -> {
                    // Strobe envelope: 2 Hz pulse scaled by intensity.
                    val pulse = ((timeSec * 2f) % 1f)
                    val envelope = if (pulse < 0.15f) 1f - pulse / 0.15f else 0f
                    glProgram.setFloatUniform("uFlash", envelope * intensity)
                    glProgram.setFloatsUniform("uFlashColor", floatArrayOf(1f, 1f, 1f))
                }

                EffectKind.BLUR -> {
                    glProgram.setIntUniform("uMode", 0)
                    glProgram.setFloatUniform("uIntensity", intensity)
                    glProgram.setFloatsUniform("uTexelSize", texelSizeUniform())
                    glProgram.setFloatsUniform("uDirection", floatArrayOf(1f, 0f))
                }

                EffectKind.MOTION_BLUR -> {
                    glProgram.setIntUniform("uMode", 1)
                    glProgram.setFloatUniform("uIntensity", intensity)
                    glProgram.setFloatsUniform("uTexelSize", texelSizeUniform())
                    glProgram.setFloatsUniform("uDirection", floatArrayOf(1f, 0.25f))
                }

                EffectKind.CINEMATIC_BLUR -> {
                    glProgram.setIntUniform("uMode", 2)
                    glProgram.setFloatUniform("uIntensity", intensity)
                    glProgram.setFloatsUniform("uTexelSize", texelSizeUniform())
                    glProgram.setFloatsUniform("uDirection", floatArrayOf(1f, 0f))
                }

                EffectKind.FILM_GRAIN -> {
                    glProgram.setIntUniform("uMode", 3)
                    glProgram.setFloatUniform("uTimeSec", timeSec)
                    glProgram.setFloatUniform("uIntensity", intensity)
                }

                EffectKind.LENS_FLARE -> {
                    glProgram.setFloatsUniform("uLightPos", floatArrayOf(0.72f, 0.3f))
                    glProgram.setFloatUniform("uIntensity", intensity)
                    glProgram.setFloatUniform("uTimeSec", timeSec)
                    glProgram.setFloatsUniform("uFlareColor", floatArrayOf(1f, 0.9f, 0.7f))
                }

                EffectKind.LIGHT_LEAK -> {
                    glProgram.setIntUniform("uMode", 0)
                    glProgram.setFloatUniform("uTimeSec", timeSec)
                    glProgram.setFloatUniform("uIntensity", intensity)
                }

                EffectKind.FILM_BURN -> {
                    glProgram.setIntUniform("uMode", 1)
                    glProgram.setFloatUniform("uTimeSec", timeSec)
                    glProgram.setFloatUniform("uIntensity", intensity)
                }

                EffectKind.WAVE -> distortion(0, intensity, timeSec)
                EffectKind.RIPPLE -> distortion(1, intensity, timeSec)
                EffectKind.WARP -> distortion(2, intensity, timeSec)
                EffectKind.FISHEYE -> distortion(3, intensity, timeSec)
                EffectKind.MIRROR -> distortion(4, intensity, timeSec)
                EffectKind.KALEIDOSCOPE -> distortion(5, intensity, timeSec)

                EffectKind.CRT -> {
                    glProgram.setFloatUniform("uTimeSec", timeSec)
                    glProgram.setFloatUniform("uIntensity", intensity)
                }

                EffectKind.OLD_FILM -> oldFilm(0, intensity, timeSec)
                EffectKind.DUST -> oldFilm(1, intensity, timeSec)
                EffectKind.SCRATCHES -> oldFilm(2, intensity, timeSec)

                EffectKind.PIXELATE -> stylize(0, intensity, timeSec)
                EffectKind.POSTERIZE -> stylize(1, intensity, timeSec)
                EffectKind.HALFTONE -> stylize(2, intensity, timeSec)
                EffectKind.DUOTONE -> stylize(3, intensity, timeSec)
                EffectKind.INVERT -> stylize(4, intensity, timeSec)
                EffectKind.THERMAL -> stylize(5, intensity, timeSec)
                EffectKind.NIGHT_VISION -> stylize(6, intensity, timeSec)
                EffectKind.EDGE_GLOW -> stylize(7, intensity, timeSec)
                EffectKind.OIL_PAINT -> stylize(8, intensity, timeSec)
                EffectKind.BLOOM -> stylize(9, intensity, timeSec)
                EffectKind.NEON_GLOW -> stylize(10, intensity, timeSec)

                EffectKind.RAIN -> atmosphere(0, intensity, timeSec)
                EffectKind.SNOW -> atmosphere(1, intensity, timeSec)
                EffectKind.FOG -> atmosphere(2, intensity, timeSec)
                EffectKind.ZOOM_PULSE -> atmosphere(3, intensity, timeSec)
                EffectKind.SPIN_BLUR -> atmosphere(4, intensity, timeSec)
                EffectKind.GOD_RAYS -> atmosphere(5, intensity, timeSec)
            }
        }

        private fun distortion(mode: Int, intensity: Float, timeSec: Float) {
            glProgram.setIntUniform("uMode", mode)
            glProgram.setFloatUniform("uIntensity", intensity)
            glProgram.setFloatUniform("uTimeSec", timeSec)
            glProgram.setFloatUniform("uAspect", aspectUniform())
        }

        private fun oldFilm(mode: Int, intensity: Float, timeSec: Float) {
            glProgram.setIntUniform("uMode", mode)
            glProgram.setFloatUniform("uTimeSec", timeSec)
            glProgram.setFloatUniform("uIntensity", intensity)
        }

        private fun stylize(mode: Int, intensity: Float, timeSec: Float) {
            glProgram.setIntUniform("uMode", mode)
            glProgram.setFloatUniform("uIntensity", intensity)
            glProgram.setFloatUniform("uTimeSec", timeSec)
            glProgram.setFloatsUniform("uTexelSize", texelSizeUniform())
        }

        private fun atmosphere(mode: Int, intensity: Float, timeSec: Float) {
            glProgram.setIntUniform("uMode", mode)
            glProgram.setFloatUniform("uIntensity", intensity)
            glProgram.setFloatUniform("uTimeSec", timeSec)
            glProgram.setFloatUniform("uAspect", aspectUniform())
            glProgram.setFloatsUniform("uTexelSize", texelSizeUniform())
        }
    }

    companion object {
        fun shaderFor(kind: EffectKind): String = when (kind) {
            EffectKind.GLITCH -> ShaderPaths.FRAGMENT_FX_GLITCH
            EffectKind.VHS -> ShaderPaths.FRAGMENT_FX_VHS
            EffectKind.RGB_SPLIT, EffectKind.CHROMATIC -> ShaderPaths.FRAGMENT_FX_RGB_SPLIT
            EffectKind.SHAKE -> ShaderPaths.FRAGMENT_FX_SHAKE
            EffectKind.FLASH -> ShaderPaths.FRAGMENT_FX_FLASH
            EffectKind.BLUR, EffectKind.MOTION_BLUR, EffectKind.CINEMATIC_BLUR ->
                ShaderPaths.FRAGMENT_FX_BLUR
            EffectKind.FILM_GRAIN, EffectKind.OLD_FILM, EffectKind.DUST, EffectKind.SCRATCHES ->
                ShaderPaths.FRAGMENT_FX_OLD_FILM
            EffectKind.LENS_FLARE -> ShaderPaths.FRAGMENT_FX_LENS_FLARE
            EffectKind.LIGHT_LEAK, EffectKind.FILM_BURN -> ShaderPaths.FRAGMENT_FX_LIGHT_LEAK
            EffectKind.WAVE, EffectKind.RIPPLE, EffectKind.WARP, EffectKind.FISHEYE,
            EffectKind.MIRROR, EffectKind.KALEIDOSCOPE,
            -> ShaderPaths.FRAGMENT_FX_DISTORTION
            EffectKind.CRT -> ShaderPaths.FRAGMENT_FX_CRT
            EffectKind.PIXELATE, EffectKind.POSTERIZE, EffectKind.HALFTONE, EffectKind.DUOTONE,
            EffectKind.INVERT, EffectKind.THERMAL, EffectKind.NIGHT_VISION, EffectKind.EDGE_GLOW,
            EffectKind.OIL_PAINT, EffectKind.BLOOM, EffectKind.NEON_GLOW,
            -> ShaderPaths.FRAGMENT_FX_STYLIZE
            EffectKind.RAIN, EffectKind.SNOW, EffectKind.FOG, EffectKind.ZOOM_PULSE,
            EffectKind.SPIN_BLUR, EffectKind.GOD_RAYS,
            -> ShaderPaths.FRAGMENT_FX_ATMOSPHERE
        }
    }
}
