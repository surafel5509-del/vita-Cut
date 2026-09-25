package com.vitacut.core.rendering.effects

import android.content.Context
import android.graphics.Bitmap
import androidx.media3.common.util.GlUtil
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import com.vitacut.core.model.BlendMode
import com.vitacut.core.rendering.gl.ShaderPaths
import com.vitacut.core.rendering.gl.VitaGlShaderProgram
import com.vitacut.core.rendering.overlay.OverlayPlacement
import kotlin.math.PI

/** Provides one overlay layer's content for a given clip-local time. */
fun interface OverlayLayerProvider {
    /** Returns null when the layer is invisible at [clipLocalTimeUs]. */
    fun overlayAt(clipLocalTimeUs: Long): OverlayLayerFrame?
}

data class OverlayLayerFrame(
    val bitmap: Bitmap,
    val placement: OverlayPlacement,
)

/**
 * Composites a single overlay layer (text, sticker, caption, image overlay) onto the frame with
 * transform + blend mode support.
 *
 * One effect instance per visible overlay, chained in z-order by the pipeline builder. The bitmap
 * is uploaded to GL only when the provider returns a *changed* bitmap (identity + generation
 * check), so static text costs one texture upload for the whole clip while animated layers
 * re-upload only when their raster actually changes.
 */
class OverlayCompositeEffect(
    private val provider: OverlayLayerProvider,
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): BaseGlShaderProgram =
        CompositeProgram(context, provider)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false

    private class CompositeProgram(
        context: Context,
        private val provider: OverlayLayerProvider,
    ) : VitaGlShaderProgram(
        context,
        ShaderPaths.VERTEX_FULLSCREEN,
        ShaderPaths.FRAGMENT_OVERLAY_COMPOSITE,
    ) {

        private var textureId: Int = -1
        private var lastBitmap: Bitmap? = null
        private var lastGeneration: Int = -1

        override fun onDrawFrame(presentationTimeUs: Long) {
            val frame = provider.overlayAt(presentationTimeUs)
            if (frame == null || frame.placement.opacity <= 0.002f) {
                glProgram.setFloatUniform("uOpacity", 0f)
                return
            }

            val bitmap = frame.bitmap
            if (textureId == -1) {
                textureId = GlUtil.generateTexture()
            }
            val generation = bitmap.generationId
            if (bitmap !== lastBitmap || generation != lastGeneration) {
                GlUtil.setTexture(textureId, bitmap)
                lastBitmap = bitmap
                lastGeneration = generation
            }

            glProgram.setSamplerTexIdUniform("uOverlayTex", textureId, /* texUnitIndex = */ 1)
            val p = frame.placement
            glProgram.setFloatsUniform(
                "uPlacement",
                floatArrayOf(p.centerX, p.centerY, p.halfWidth, p.halfHeight),
            )
            glProgram.setFloatUniform("uRotationRad", (p.rotationDegrees * PI / 180.0).toFloat())
            glProgram.setFloatUniform("uOpacity", p.opacity.coerceIn(0f, 1f))
            glProgram.setIntUniform("uBlend", blendIndex(p.blendMode))
        }

        override fun release() {
            if (textureId != -1) {
                runCatching { GlUtil.deleteTexture(textureId) }
                textureId = -1
            }
            lastBitmap = null
            super.release()
        }
    }

    companion object {
        fun blendIndex(mode: BlendMode): Int = when (mode) {
            BlendMode.NORMAL -> 0
            BlendMode.SCREEN -> 1
            BlendMode.MULTIPLY -> 2
            BlendMode.OVERLAY -> 3
            BlendMode.ADD -> 4
            BlendMode.DARKEN -> 5
            BlendMode.LIGHTEN -> 6
        }
    }
}
