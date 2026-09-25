package com.vitacut.core.rendering.gl

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import com.vitacut.core.common.logging.VitaLog

/**
 * Base class for all Vita Cut GLSL effect programs.
 *
 * Follows the exact extension pattern of Media3's own effects (compile a [GlProgram] from the
 * asset shaders, bind a fullscreen quad, set per-frame uniforms in [onDrawFrame]). Subclasses
 * only implement uniform uploading, which keeps every effect time-varying-capable for free:
 * uniforms are re-evaluated on every frame from the presentation timestamp.
 */
abstract class VitaGlShaderProgram(
    context: Context,
    vertexShaderAssetPath: String,
    fragmentShaderAssetPath: String,
    useHdr: Boolean = false,
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents = */ useHdr, /* texturePoolCapacity = */ 1) {

    protected val glProgram: GlProgram = try {
        GlProgram(context, vertexShaderAssetPath, fragmentShaderAssetPath)
    } catch (e: Exception) {
        throw VideoFrameProcessingException(e)
    }

    private var outputWidth: Int = 1
    private var outputHeight: Int = 1

    init {
        // Fullscreen quad in NDC, with matching texture coordinates.
        glProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
        glProgram.setBufferAttribute(
            "aTexPosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
        glProgram.setFloatsUniform("uTransformationMatrix", GlUtil.create4x4IdentityMatrix())
        glProgram.setFloatsUniform("uTexTransformationMatrix", GlUtil.create4x4IdentityMatrix())
    }

    /** Output size defaults to the input size; override [computeOutputSize] for resizing effects. */
    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        val out = computeOutputSize(inputWidth, inputHeight)
        outputWidth = out.width.coerceAtLeast(2)
        outputHeight = out.height.coerceAtLeast(2)
        onConfigure(inputWidth, inputHeight)
        return Size(outputWidth, outputHeight)
    }

    protected open fun computeOutputSize(inputWidth: Int, inputHeight: Int): Size =
        Size(inputWidth, inputHeight)

    protected open fun onConfigure(inputWidth: Int, inputHeight: Int) = Unit

    protected fun texelSizeUniform(): FloatArray =
        floatArrayOf(1f / outputWidth.coerceAtLeast(1), 1f / outputHeight.coerceAtLeast(1))

    protected fun aspectUniform(): Float = outputWidth.toFloat() / outputHeight.coerceAtLeast(1)

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex = */ 0)
            onDrawFrame(presentationTimeUs)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first = */ 0, /* count = */ 4)
        } catch (e: GlUtil.GlException) {
            VitaLog.e("VitaGlShaderProgram", "drawFrame failed", e)
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    /** Upload per-frame uniforms (time, intensity, transform…) before the draw call binds them. */
    protected abstract fun onDrawFrame(presentationTimeUs: Long)

    override fun release() {
        try {
            glProgram.delete()
        } catch (e: GlUtil.GlException) {
            VitaLog.w("VitaGlShaderProgram", "Failed to delete GL program", e)
        }
        super.release()
    }
}

/** Asset locations of the shared shader library (merged into the APK from core:rendering). */
object ShaderPaths {
    const val VERTEX_FULLSCREEN = "shaders/vertex_fullscreen_es2.glsl"

    const val FRAGMENT_COLOR_GRADE = "shaders/fragment_color_grade_es2.glsl"
    const val FRAGMENT_CURVES = "shaders/fragment_curves_es2.glsl"
    const val FRAGMENT_HSL_BANDS = "shaders/fragment_hsl_bands_es2.glsl"
    const val FRAGMENT_LUT3D = "shaders/fragment_lut3d_es2.glsl"

    const val FRAGMENT_FX_GLITCH = "shaders/fragment_fx_glitch_es2.glsl"
    const val FRAGMENT_FX_VHS = "shaders/fragment_fx_vhs_es2.glsl"
    const val FRAGMENT_FX_RGB_SPLIT = "shaders/fragment_fx_rgbsplit_es2.glsl"
    const val FRAGMENT_FX_SHAKE = "shaders/fragment_fx_shake_es2.glsl"
    const val FRAGMENT_FX_FLASH = "shaders/fragment_fx_flash_es2.glsl"
    const val FRAGMENT_FX_BLUR = "shaders/fragment_fx_blur_es2.glsl"
    const val FRAGMENT_FX_LENS_FLARE = "shaders/fragment_fx_lensflare_es2.glsl"
    const val FRAGMENT_FX_LIGHT_LEAK = "shaders/fragment_fx_lightleak_es2.glsl"
    const val FRAGMENT_FX_DISTORTION = "shaders/fragment_fx_distortion_es2.glsl"
    const val FRAGMENT_FX_CRT = "shaders/fragment_fx_crt_es2.glsl"
    const val FRAGMENT_FX_OLD_FILM = "shaders/fragment_fx_oldfilm_es2.glsl"

    const val FRAGMENT_MASK = "shaders/fragment_mask_es2.glsl"
    const val FRAGMENT_CHROMA_KEY = "shaders/fragment_chroma_key_es2.glsl"
    const val FRAGMENT_TRANSITION = "shaders/fragment_transition_es2.glsl"
    const val FRAGMENT_CANVAS_BACKGROUND = "shaders/fragment_canvas_background_es2.glsl"
    const val FRAGMENT_OVERLAY_COMPOSITE = "shaders/fragment_overlay_composite_es2.glsl"
}
