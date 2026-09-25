package com.vitacut.core.rendering.effects

import android.content.Context
import android.graphics.Bitmap
import androidx.media3.common.util.GlUtil
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import com.vitacut.core.model.MaskSettings
import com.vitacut.core.model.MaskShape
import com.vitacut.core.rendering.gl.ShaderPaths
import com.vitacut.core.rendering.gl.VitaGlShaderProgram
import kotlin.math.PI

/**
 * Masking effect (rectangle, circle, linear, radial, freeform-via-texture).
 *
 * All mask parameters are time-varying: [maskAt] is evaluated per frame so keyframed animated
 * masks (position/scale/rotation/feather) work identically in preview and export.
 * Freeform masks are rasterized by the caller into a bitmap ([freeformMaskProvider]).
 */
class MaskEffect(
    private val maskAt: (timeUs: Long) -> MaskSettings,
    private val freeformMaskProvider: (() -> Bitmap?)? = null,
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): BaseGlShaderProgram =
        MaskProgram(context, maskAt, freeformMaskProvider)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false

    private class MaskProgram(
        context: Context,
        private val maskAt: (Long) -> MaskSettings,
        private val freeformMaskProvider: (() -> Bitmap?)?,
    ) : VitaGlShaderProgram(context, ShaderPaths.VERTEX_FULLSCREEN, ShaderPaths.FRAGMENT_MASK) {

        private var maskTextureId: Int = -1
        private var maskUploaded = false

        override fun onDrawFrame(presentationTimeUs: Long) {
            val mask = maskAt(presentationTimeUs)
            val shapeInt = when (mask.shape) {
                MaskShape.NONE -> 0
                MaskShape.RECTANGLE -> 1
                MaskShape.CIRCLE -> 2
                MaskShape.LINEAR -> 3
                MaskShape.RADIAL -> 4
                MaskShape.FREEFORM -> 5
            }
            glProgram.setIntUniform("uShape", shapeInt)

            if (mask.shape == MaskShape.FREEFORM) {
                if (!maskUploaded) {
                    maskUploaded = true
                    val bitmap = freeformMaskProvider?.invoke()
                    if (bitmap != null) {
                        maskTextureId = GlUtil.generateTexture()
                        GlUtil.setTexture(maskTextureId, bitmap)
                    }
                }
                if (maskTextureId != -1) {
                    glProgram.setSamplerTexIdUniform("uMaskTex", maskTextureId, 1)
                } else {
                    glProgram.setIntUniform("uShape", 0) // no mask available → passthrough
                }
                glProgram.setFloatUniform("uInvert", if (mask.invert) 1f else 0f)
                return
            }

            glProgram.setFloatsUniform(
                "uCenter",
                // Model center is NDC (center-origin, y-up); shader wants 0..1 frame space, y-down.
                floatArrayOf(mask.centerX * 0.5f + 0.5f, 0.5f - mask.centerY * 0.5f),
            )
            glProgram.setFloatsUniform("uScale", floatArrayOf(mask.scaleX, mask.scaleY))
            glProgram.setFloatUniform("uRotationRad", (mask.rotationDegrees * PI / 180.0).toFloat())
            glProgram.setFloatUniform("uFeather", mask.feather.coerceIn(0f, 1f))
            glProgram.setFloatUniform("uInvert", if (mask.invert) 1f else 0f)
            glProgram.setFloatUniform("uAspect", aspectUniform())
        }

        override fun release() {
            if (maskTextureId != -1) {
                runCatching { GlUtil.deleteTexture(maskTextureId) }
                maskTextureId = -1
            }
            super.release()
        }
    }
}
