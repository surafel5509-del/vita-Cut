package com.vitacut.core.rendering.effects

import android.content.Context
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import com.vitacut.core.model.HslAdjustments
import com.vitacut.core.model.HslBand
import com.vitacut.core.rendering.gl.ShaderPaths
import com.vitacut.core.rendering.gl.VitaGlShaderProgram

/** Per-band HSL adjustments (8 hue bands), time-varying for keyframe support. */
class HslBandsEffect(
    private val adjustmentsAt: (timeUs: Long) -> HslAdjustments,
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): BaseGlShaderProgram =
        HslBandsProgram(context, adjustmentsAt)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false

    private class HslBandsProgram(
        context: Context,
        private val adjustmentsAt: (Long) -> HslAdjustments,
    ) : VitaGlShaderProgram(context, ShaderPaths.VERTEX_FULLSCREEN, ShaderPaths.FRAGMENT_HSL_BANDS) {

        override fun onDrawFrame(presentationTimeUs: Long) {
            val adjustments = adjustmentsAt(presentationTimeUs)
            HslBand.entries.forEachIndexed { index, band ->
                val a = adjustments.forBand(band)
                glProgram.setFloatUniform("uHue$index", a.hueDegrees / 360f)
                glProgram.setFloatUniform("uSat$index", a.saturation.coerceIn(-1f, 1f))
                glProgram.setFloatUniform("uLum$index", a.luminance.coerceIn(-1f, 1f))
            }
        }
    }
}
