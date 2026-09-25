package com.vitacut.core.rendering.effects

import android.content.Context
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import com.vitacut.core.model.ChromaKeySettings
import com.vitacut.core.model.KeyBackground
import com.vitacut.core.rendering.gl.ShaderPaths
import com.vitacut.core.rendering.gl.VitaGlShaderProgram

/**
 * Chroma-key (green screen) effect.
 *
 * Solid-color and transparent backgrounds are composited inside the shader. Image/video
 * backgrounds are handled one level up by [com.vitacut.core.rendering.pipeline.EffectsPipelineBuilder]:
 * the keyed clip outputs premultiplied alpha and is placed above the background layer by the
 * compositor (export) — the shader contract is identical for preview.
 */
class ChromaKeyEffect(
    private val settingsAt: (timeUs: Long) -> ChromaKeySettings,
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): BaseGlShaderProgram =
        ChromaKeyProgram(context, settingsAt)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false

    private class ChromaKeyProgram(
        context: Context,
        private val settingsAt: (Long) -> ChromaKeySettings,
    ) : VitaGlShaderProgram(context, ShaderPaths.VERTEX_FULLSCREEN, ShaderPaths.FRAGMENT_CHROMA_KEY) {

        override fun onDrawFrame(presentationTimeUs: Long) {
            val s = settingsAt(presentationTimeUs)
            val key = s.keyColorArgb
            glProgram.setFloatsUniform(
                "uKeyColor",
                floatArrayOf(
                    ((key shr 16) and 0xFF) / 255f,
                    ((key shr 8) and 0xFF) / 255f,
                    (key and 0xFF) / 255f,
                ),
            )
            glProgram.setFloatUniform("uSimilarity", s.intensity.coerceIn(0f, 1f))
            glProgram.setFloatUniform("uEdge", s.edge.coerceIn(0f, 1f))
            glProgram.setFloatUniform("uFeather", s.feather.coerceIn(0f, 1f))
            glProgram.setFloatUniform("uSpill", s.spillSuppression.coerceIn(0f, 1f))
            glProgram.setFloatUniform("uShadow", s.shadow.coerceIn(0f, 1f))

            val bg = s.background
            if (bg is KeyBackground.SolidColor) {
                glProgram.setIntUniform("uBgMode", 1)
                glProgram.setFloatsUniform(
                    "uBgColor",
                    floatArrayOf(
                        ((bg.argb shr 16) and 0xFF) / 255f,
                        ((bg.argb shr 8) and 0xFF) / 255f,
                        (bg.argb and 0xFF) / 255f,
                    ),
                )
            } else {
                // Transparent (composited downstream) or image/video (handled by the pipeline).
                glProgram.setIntUniform("uBgMode", 0)
                glProgram.setFloatsUniform("uBgColor", floatArrayOf(0f, 0f, 0f))
            }
        }
    }
}
