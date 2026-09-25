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

                EffectKind.RGB_SPLIT -> {
                    glProgram.setFloatUniform("uIntensity", intensity)
                    glProgram.setFloatUniform("uAngleDeg", 0f)
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
                    // Old-film shader, grain-only mode (3).
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

                EffectKind.WAVE -> {
                    glProgram.setIntUniform("uMode", 0)
                    glProgram.setFloatUniform("uIntensity", intensity)
                    glProgram.setFloatUniform("uTimeSec", timeSec)
                    glProgram.setFloatUniform("uAspect", aspectUniform())
                }

                EffectKind.RIPPLE -> {
                    glProgram.setIntUniform("uMode", 1)
                    glProgram.setFloatUniform("uIntensity", intensity)
                    glProgram.setFloatUniform("uTimeSec", timeSec)
                    glProgram.setFloatUniform("uAspect", aspectUniform())
                }

                EffectKind.WARP -> {
                    glProgram.setIntUniform("uMode", 2)
                    glProgram.setFloatUniform("uIntensity", intensity)
                    glProgram.setFloatUniform("uTimeSec", timeSec)
                    glProgram.setFloatUniform("uAspect", aspectUniform())
                }

                EffectKind.FISHEYE -> {
                    glProgram.setIntUniform("uMode", 3)
                    glProgram.setFloatUniform("uIntensity", intensity)
                    glProgram.setFloatUniform("uTimeSec", timeSec)
                    glProgram.setFloatUniform("uAspect", aspectUniform())
                }

                EffectKind.CRT -> {
                    glProgram.setFloatUniform("uTimeSec", timeSec)
                    glProgram.setFloatUniform("uIntensity", intensity)
                }

                EffectKind.OLD_FILM -> {
                    glProgram.setIntUniform("uMode", 0)
                    glProgram.setFloatUniform("uTimeSec", timeSec)
                    glProgram.setFloatUniform("uIntensity", intensity)
                }

                EffectKind.DUST -> {
                    glProgram.setIntUniform("uMode", 1)
                    glProgram.setFloatUniform("uTimeSec", timeSec)
                    glProgram.setFloatUniform("uIntensity", intensity)
                }

                EffectKind.SCRATCHES -> {
                    glProgram.setIntUniform("uMode", 2)
                    glProgram.setFloatUniform("uTimeSec", timeSec)
                    glProgram.setFloatUniform("uIntensity", intensity)
                }
            }
        }
    }

    companion object {
        fun shaderFor(kind: EffectKind): String = when (kind) {
            EffectKind.GLITCH -> ShaderPaths.FRAGMENT_FX_GLITCH
            EffectKind.VHS -> ShaderPaths.FRAGMENT_FX_VHS
            EffectKind.RGB_SPLIT -> ShaderPaths.FRAGMENT_FX_RGB_SPLIT
            EffectKind.SHAKE -> ShaderPaths.FRAGMENT_FX_SHAKE
            EffectKind.FLASH -> ShaderPaths.FRAGMENT_FX_FLASH
            EffectKind.BLUR, EffectKind.MOTION_BLUR, EffectKind.CINEMATIC_BLUR ->
                ShaderPaths.FRAGMENT_FX_BLUR
            EffectKind.FILM_GRAIN, EffectKind.OLD_FILM, EffectKind.DUST, EffectKind.SCRATCHES ->
                ShaderPaths.FRAGMENT_FX_OLD_FILM
            EffectKind.LENS_FLARE -> ShaderPaths.FRAGMENT_FX_LENS_FLARE
            EffectKind.LIGHT_LEAK, EffectKind.FILM_BURN -> ShaderPaths.FRAGMENT_FX_LIGHT_LEAK
            EffectKind.WAVE, EffectKind.RIPPLE, EffectKind.WARP, EffectKind.FISHEYE ->
                ShaderPaths.FRAGMENT_FX_DISTORTION
            EffectKind.CRT -> ShaderPaths.FRAGMENT_FX_CRT
        }
    }
}
