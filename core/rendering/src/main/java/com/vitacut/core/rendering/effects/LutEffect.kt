package com.vitacut.core.rendering.effects

import android.content.Context
import android.graphics.Bitmap
import androidx.media3.common.util.GlUtil
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import com.vitacut.core.common.logging.VitaLog
import com.vitacut.core.rendering.gl.ShaderPaths
import com.vitacut.core.rendering.gl.VitaGlShaderProgram
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.roundToInt

/**
 * Parser & texture baker for `.cube` 3D LUT files (the industry-standard interchange format).
 *
 * A cube LUT with size N is baked into an (N*N) x N horizontal strip bitmap where slice `b`
 * occupies columns [b*N, (b+1)*N). The fragment shader does trilinear interpolation between
 * slices. Pure Kotlin, unit-testable, no native code.
 */
object CubeLut {

    data class Lut3d(
        val size: Int,
        /** Row-major data: index = r + g*size + b*size*size, values 0..1 RGB. */
        val data: FloatArray,
    )

    /** Parses an .cube stream. Returns null on malformed input (never throws). */
    fun parse(input: InputStream): Lut3d? = try {
        BufferedReader(InputStreamReader(input)).use { reader ->
            var size = -1
            val values = mutableListOf<Float>()
            var line = reader.readLine()
            while (line != null) {
                val trimmed = line.trim()
                when {
                    trimmed.isEmpty() || trimmed.startsWith("#") -> Unit
                    trimmed.startsWith("TITLE") -> Unit
                    trimmed.startsWith("LUT_3D_SIZE") -> {
                        size = trimmed.substringAfter("LUT_3D_SIZE").trim().toIntOrNull() ?: -1
                    }
                    trimmed.startsWith("DOMAIN_") -> Unit // domain limits are rare; treat as full
                    else -> {
                        val parts = trimmed.split(Regex("\\s+"))
                        if (parts.size >= 3) {
                            val r = parts[0].toFloatOrNull()
                            val g = parts[1].toFloatOrNull()
                            val b = parts[2].toFloatOrNull()
                            if (r != null && g != null && b != null) {
                                values += r; values += g; values += b
                            }
                        }
                    }
                }
                line = reader.readLine()
            }
            if (size < 2 || values.size != size * size * size * 3) {
                VitaLog.w("CubeLut", "Malformed LUT: size=$size values=${values.size}")
                null
            } else {
                // .cube files list entries red-fastest; our indexing matches (r + g*N + b*N*N).
                Lut3d(size, values.toFloatArray())
            }
        }
    } catch (t: Throwable) {
        VitaLog.w("CubeLut", "Failed to parse LUT: ${t.message}")
        null
    }

    /** Bakes [lut] into a horizontal-strip ARGB bitmap for the shader. */
    fun bakeToBitmap(lut: Lut3d): Bitmap {
        val n = lut.size
        val bitmap = Bitmap.createBitmap(n * n, n, Bitmap.Config.ARGB_8888)
        for (b in 0 until n) {
            for (g in 0 until n) {
                for (r in 0 until n) {
                    val index = (r + g * n + b * n * n) * 3
                    val red = (lut.data[index].coerceIn(0f, 1f) * 255f).roundToInt()
                    val green = (lut.data[index + 1].coerceIn(0f, 1f) * 255f).roundToInt()
                    val blue = (lut.data[index + 2].coerceIn(0f, 1f) * 255f).roundToInt()
                    bitmap.setPixel(b * n + r, n - 1 - g, android.graphics.Color.rgb(red, green, blue))
                }
            }
        }
        return bitmap
    }

    /** Generates an identity LUT of [size] — used for tests and as the neutral default. */
    fun identity(size: Int = 16): Lut3d {
        val data = FloatArray(size * size * size * 3)
        var i = 0
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    data[i++] = r / (size - 1f)
                    data[i++] = g / (size - 1f)
                    data[i++] = b / (size - 1f)
                }
            }
        }
        return Lut3d(size, data)
    }
}

/**
 * 3D LUT effect. The (immutable) baked LUT is uploaded once per shader program; intensity can be
 * keyframed. LUT files come from app assets or user imports (SAF), resolved by the repository
 * layer into a [Bitmap] before constructing this effect.
 */
class LutEffect(
    private val lutBitmapProvider: () -> Bitmap?,
    private val lutSizeProvider: () -> Int,
    private val intensityAt: (timeUs: Long) -> Float = { 1f },
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): BaseGlShaderProgram =
        LutProgram(context, lutBitmapProvider, lutSizeProvider, intensityAt)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false

    private class LutProgram(
        context: Context,
        private val lutBitmapProvider: () -> Bitmap?,
        private val lutSizeProvider: () -> Int,
        private val intensityAt: (Long) -> Float,
    ) : VitaGlShaderProgram(context, ShaderPaths.VERTEX_FULLSCREEN, ShaderPaths.FRAGMENT_LUT3D) {

        private var textureId: Int = -1
        private var uploaded = false

        override fun onDrawFrame(presentationTimeUs: Long) {
            if (!uploaded) {
                val bitmap = lutBitmapProvider()
                if (bitmap != null) {
                    textureId = GlUtil.generateTexture()
                    GlUtil.setTexture(textureId, bitmap)
                }
                uploaded = true
            }
            if (textureId == -1) {
                glProgram.setFloatUniform("uIntensity", 0f)
                return
            }
            glProgram.setSamplerTexIdUniform("uLutTexture", textureId, /* texUnitIndex = */ 1)
            glProgram.setFloatUniform("uLutSize", lutSizeProvider().toFloat())
            glProgram.setFloatUniform("uIntensity", intensityAt(presentationTimeUs).coerceIn(0f, 1f))
        }

        override fun release() {
            if (textureId != -1) {
                runCatching { GlUtil.deleteTexture(textureId) }
                textureId = -1
            }
            super.release()
        }
    }
}


