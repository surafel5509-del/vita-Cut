package com.vitacut.core.rendering.effects

import android.content.Context
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import com.vitacut.core.model.ColorAdjustments
import com.vitacut.core.rendering.gl.ShaderPaths
import com.vitacut.core.rendering.gl.VitaGlShaderProgram

/**
 * Time-varying color grade (all adjustment sliders).
 *
 * [paramsAt] is evaluated on the GL thread for every frame with the *clip-local* presentation
 * time, which lets keyframes animate any slider at full frame rate. The same class serves
 * preview (ExoPlayer.setVideoEffects) and export (Transformer EditedMediaItem effects), so what
 * you grade is what you export.
 */
class ColorGradeEffect(
    private val paramsAt: (timeUs: Long) -> ColorAdjustments,
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): BaseGlShaderProgram =
        ColorGradeProgram(context, paramsAt)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false

    private class ColorGradeProgram(
        context: Context,
        private val paramsAt: (Long) -> ColorAdjustments,
    ) : VitaGlShaderProgram(context, ShaderPaths.VERTEX_FULLSCREEN, ShaderPaths.FRAGMENT_COLOR_GRADE) {

        override fun onDrawFrame(presentationTimeUs: Long) {
            val p = paramsAt(presentationTimeUs)
            val texel = texelSizeUniform()
            glProgram.setFloatsUniform("uTexelSize", texel)
            glProgram.setFloatUniform("uTimeSec", presentationTimeUs / 1_000_000f)
            glProgram.setFloatUniform("uBrightness", p.brightness)
            glProgram.setFloatUniform("uExposure", p.exposure)
            glProgram.setFloatUniform("uContrast", p.contrast)
            glProgram.setFloatUniform("uSaturation", p.saturation)
            glProgram.setFloatUniform("uVibrance", p.vibrance)
            glProgram.setFloatUniform("uTemperature", p.temperature)
            glProgram.setFloatUniform("uTint", p.tint)
            glProgram.setFloatUniform("uHighlights", p.highlights)
            glProgram.setFloatUniform("uShadows", p.shadows)
            glProgram.setFloatUniform("uWhites", p.whites)
            glProgram.setFloatUniform("uBlacks", p.blacks)
            glProgram.setFloatUniform("uFade", p.fade)
            glProgram.setFloatUniform("uSharpen", p.sharpen)
            glProgram.setFloatUniform("uClarity", p.clarity)
            glProgram.setFloatUniform("uVignette", p.vignette)
            glProgram.setFloatUniform("uGrain", p.grain)
        }
    }
}

/** Thrown wrapper kept for symmetry with Media3 error reporting. */
internal fun wrapGlFailure(cause: Throwable): VideoFrameProcessingException =
    VideoFrameProcessingException(cause)
