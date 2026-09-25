package com.vitacut.core.rendering.effects

import android.content.Context
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import com.vitacut.core.model.CanvasBackground
import com.vitacut.core.model.ContentFit
import com.vitacut.core.model.CropSettings
import com.vitacut.core.model.SpatialTransform
import com.vitacut.core.rendering.gl.ShaderPaths
import com.vitacut.core.rendering.gl.VitaGlShaderProgram
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min

/**
 * Fused clip placement: maps the source frame onto the canvas with crop + content-fit +
 * spatial transform + opacity + background in a single resampling pass.
 *
 * All parameters can vary per frame ([transformAt] feeds keyframed transforms). The output size
 * is the canvas resolution, which makes every clip in an export produce identically sized frames
 * for the compositor, and gives preview pixel parity with export.
 */
class ClipPlacementEffect(
    private val canvasWidth: Int,
    private val canvasHeight: Int,
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val contentFit: ContentFit,
    private val crop: CropSettings,
    private val background: CanvasBackground,
    private val transformAt: (timeUs: Long) -> SpatialTransform,
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): BaseGlShaderProgram =
        PlacementProgram(
            context, canvasWidth, canvasHeight, sourceWidth, sourceHeight,
            contentFit, crop, background, transformAt,
        )

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false

    private class PlacementProgram(
        context: Context,
        private val canvasWidth: Int,
        private val canvasHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        contentFit: ContentFit,
        crop: CropSettings,
        private val background: CanvasBackground,
        private val transformAt: (Long) -> SpatialTransform,
    ) : VitaGlShaderProgram(context, ShaderPaths.VERTEX_FULLSCREEN, ShaderPaths.FRAGMENT_CANVAS_BACKGROUND) {

        private val uvScale: FloatArray
        private val cropCenter: FloatArray
        private val cropHalf: FloatArray

        init {
            val cropW = max(1f, crop.widthFraction * sourceWidth)
            val cropH = max(1f, crop.heightFraction * sourceHeight)
            cropCenter = floatArrayOf(
                (crop.left + crop.right) * 0.5f,
                // Source uv is bottom-up in GL; crop uses top-down fractions — flip Y here once.
                1f - (crop.top + crop.bottom) * 0.5f,
            )
            cropHalf = floatArrayOf(crop.widthFraction * 0.5f, crop.heightFraction * 0.5f)

            uvScale = when (contentFit) {
                ContentFit.FILL -> floatArrayOf(crop.widthFraction, crop.heightFraction)

                ContentFit.COVER -> {
                    // Scale so the crop window covers the canvas; visible extent shrinks.
                    val s = max(canvasWidth / cropW, canvasHeight / cropH)
                    floatArrayOf(canvasWidth / (s * sourceWidth), canvasHeight / (s * sourceHeight))
                }

                ContentFit.FIT -> {
                    // Scale so the crop window fits inside; extent grows → background visible.
                    val s = min(canvasWidth / cropW, canvasHeight / cropH)
                    floatArrayOf(canvasWidth / (s * sourceWidth), canvasHeight / (s * sourceHeight))
                }
            }
        }

        override fun computeOutputSize(inputWidth: Int, inputHeight: Int): Size =
            Size(canvasWidth, canvasHeight)

        override fun onDrawFrame(presentationTimeUs: Long) {
            val t = transformAt(presentationTimeUs)
            glProgram.setFloatsUniform("uUvScale", uvScale)
            glProgram.setFloatsUniform("uCropCenter", cropCenter)
            glProgram.setFloatsUniform("uCropHalf", cropHalf)
            glProgram.setFloatsUniform("uTranslation", floatArrayOf(t.translationX, t.translationY))
            glProgram.setFloatsUniform(
                "uScaleAbs",
                floatArrayOf(t.scaleX.coerceAtLeast(0.001f), t.scaleY.coerceAtLeast(0.001f)),
            )
            glProgram.setFloatsUniform(
                "uFlip",
                floatArrayOf(if (t.flipHorizontal) -1f else 1f, if (t.flipVertical) -1f else 1f),
            )
            glProgram.setFloatUniform("uRotationRad", (t.rotationDegrees * PI / 180.0).toFloat())
            glProgram.setFloatUniform("uOpacity", t.opacity.coerceIn(0f, 1f))

            when (val bg = background) {
                is CanvasBackground.Color -> {
                    glProgram.setIntUniform("uBgMode", 1)
                    glProgram.setFloatsUniform("uBgColor0", rgb(bg.argb))
                    glProgram.setFloatsUniform("uBgColor1", rgb(bg.argb))
                }

                is CanvasBackground.Gradient -> {
                    glProgram.setIntUniform("uBgMode", 2)
                    glProgram.setFloatsUniform("uBgColor0", rgb(bg.startArgb))
                    glProgram.setFloatsUniform("uBgColor1", rgb(bg.endArgb))
                }

                CanvasBackground.BlurFill -> {
                    glProgram.setIntUniform("uBgMode", 3)
                    glProgram.setFloatsUniform("uBgColor0", floatArrayOf(0f, 0f, 0f))
                    glProgram.setFloatsUniform("uBgColor1", floatArrayOf(0f, 0f, 0f))
                }

                else -> {
                    // Black, or image backgrounds (resolved to a bitmap upstream; falls back to
                    // black when the image cannot be loaded).
                    glProgram.setIntUniform("uBgMode", 0)
                    glProgram.setFloatsUniform("uBgColor0", floatArrayOf(0f, 0f, 0f))
                    glProgram.setFloatsUniform("uBgColor1", floatArrayOf(0f, 0f, 0f))
                }
            }
        }

        private fun rgb(argb: Int): FloatArray = floatArrayOf(
            ((argb shr 16) and 0xFF) / 255f,
            ((argb shr 8) and 0xFF) / 255f,
            (argb and 0xFF) / 255f,
        )
    }
}
