package com.vitacut.core.rendering.effects

import android.content.Context
import android.graphics.Bitmap
import androidx.media3.common.util.GlUtil
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import com.vitacut.core.model.ToneCurves
import com.vitacut.core.rendering.gl.ShaderPaths
import com.vitacut.core.rendering.gl.VitaGlShaderProgram

/**
 * Bakes [ToneCurves] control points into a 256x4 RGBA lookup texture (rows: RGB master, red,
 * green, blue). Baking on the CPU keeps the fragment shader a single texture fetch per channel
 * and makes curves free at playback time.
 */
object CurvesLutBaker {

    const val LUT_SIZE = 256

    fun bake(curves: ToneCurves): Bitmap {
        val bitmap = Bitmap.createBitmap(LUT_SIZE, 4, Bitmap.Config.ARGB_8888)
        val channels = listOf(curves.rgb, curves.red, curves.green, curves.blue)
        for (row in 0 until 4) {
            val points = channels[row]
            for (x in 0 until LUT_SIZE) {
                val input = x / (LUT_SIZE - 1f)
                // Master curve first, then the per-channel curve.
                val viaMaster = ToneCurves.evaluate(points = curves.rgb, x = input)
                val output = if (row == 0) viaMaster
                else ToneCurves.evaluate(points, viaMaster)
                val byte = (output.coerceIn(0f, 1f) * 255).toInt()
                bitmap.setPixel(x, row, android.graphics.Color.rgb(byte, byte, byte))
            }
        }
        return bitmap
    }
}

/** RGB tone curves effect driven by a baked LUT texture; intensity is keyframable. */
class CurvesEffect(
    private val curvesAt: (timeUs: Long) -> ToneCurves,
    private val intensityAt: (timeUs: Long) -> Float = { 1f },
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): BaseGlShaderProgram =
        CurvesProgram(context, curvesAt, intensityAt)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false

    private class CurvesProgram(
        context: Context,
        private val curvesAt: (Long) -> ToneCurves,
        private val intensityAt: (Long) -> Float,
    ) : VitaGlShaderProgram(context, ShaderPaths.VERTEX_FULLSCREEN, ShaderPaths.FRAGMENT_CURVES) {

        private var lutTextureId: Int = -1
        private var bakedFor: ToneCurves? = null

        override fun onDrawFrame(presentationTimeUs: Long) {
            val curves = curvesAt(presentationTimeUs)
            if (curves != bakedFor || lutTextureId == -1) {
                val bitmap = CurvesLutBaker.bake(curves)
                if (lutTextureId == -1) {
                    lutTextureId = GlUtil.generateTexture()
                }
                GlUtil.setTexture(lutTextureId, bitmap)
                bitmap.recycle()
                bakedFor = curves
            }
            glProgram.setSamplerTexIdUniform("uCurveLut", lutTextureId, /* texUnitIndex = */ 1)
            glProgram.setFloatUniform("uIntensity", intensityAt(presentationTimeUs).coerceIn(0f, 1f))
        }

        override fun release() {
            if (lutTextureId != -1) {
                runCatching { GlUtil.deleteTexture(lutTextureId) }
                lutTextureId = -1
            }
            super.release()
        }
    }
}
