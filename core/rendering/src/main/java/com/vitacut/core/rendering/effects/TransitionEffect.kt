package com.vitacut.core.rendering.effects

import android.content.Context
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import com.vitacut.core.model.TransitionDirection
import com.vitacut.core.model.TransitionKind
import com.vitacut.core.model.TransitionState
import com.vitacut.core.rendering.gl.ShaderPaths
import com.vitacut.core.rendering.gl.VitaGlShaderProgram

/**
 * Transition edge effect for one clip boundary.
 *
 * [transition] is the head (in) or tail (out) transition of the clip; [clipDurationUs] is the
 * clip's timeline duration. Progress is computed per frame from the clip-local presentation
 * time: for a tail transition it ramps 0→1 over the last [TransitionState.durationUs]; for a
 * head transition it ramps 1→0 over the first [TransitionState.durationUs].
 */
class TransitionEffect(
    private val transition: TransitionState,
    private val clipDurationUs: Long,
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): BaseGlShaderProgram =
        TransitionProgram(context, transition, clipDurationUs)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        transition.durationUs <= 0L || transition.intensity <= 0f

    private class TransitionProgram(
        context: Context,
        private val transition: TransitionState,
        private val clipDurationUs: Long,
    ) : VitaGlShaderProgram(context, ShaderPaths.VERTEX_FULLSCREEN, ShaderPaths.FRAGMENT_TRANSITION) {

        override fun onDrawFrame(presentationTimeUs: Long) {
            val duration = transition.durationUs.coerceAtLeast(1L)
            val progress = if (transition.atEnd) {
                val windowStart = clipDurationUs - duration
                if (presentationTimeUs < windowStart) 0f
                else ((presentationTimeUs - windowStart).toFloat() / duration).coerceIn(0f, 1f)
            } else {
                if (presentationTimeUs >= duration) 0f
                else 1f - (presentationTimeUs.toFloat() / duration).coerceIn(0f, 1f)
            }

            glProgram.setIntUniform("uKind", kindIndex(transition.kind))
            glProgram.setIntUniform("uDir", dirIndex(transition.direction))
            glProgram.setFloatUniform("uProgress", progress)
            glProgram.setFloatUniform("uIntensity", transition.intensity.coerceIn(0f, 1f))
            glProgram.setFloatUniform("uTimeSec", presentationTimeUs / 1_000_000f)
            glProgram.setFloatUniform("uAspect", aspectUniform())
            glProgram.setFloatsUniform("uTexelSize", texelSizeUniform())
        }
    }

    companion object {
        /** Stable shader-side indices; must match the numbering in fragment_transition_es2.glsl. */
        fun kindIndex(kind: TransitionKind): Int = when (kind) {
            TransitionKind.FADE -> 0
            TransitionKind.DISSOLVE_TO_BLACK -> 1
            TransitionKind.DISSOLVE_TO_WHITE -> 2
            TransitionKind.BLUR_IN -> 3
            TransitionKind.BLUR_OUT -> 4
            TransitionKind.ZOOM_IN -> 5
            TransitionKind.ZOOM_OUT -> 6
            TransitionKind.SPIN_IN -> 7
            TransitionKind.SPIN_OUT -> 8
            TransitionKind.SLIDE -> 9
            TransitionKind.PUSH -> 10
            TransitionKind.SHAKE_IN -> 11
            TransitionKind.GLITCH_IN -> 12
            TransitionKind.GLITCH_OUT -> 13
            TransitionKind.FLASH_IN -> 14
            TransitionKind.FLASH_OUT -> 15
            TransitionKind.LIGHT_SWEEP -> 16
            TransitionKind.LIGHT_LEAK_IN -> 17
            TransitionKind.CUBE_SPIN -> 18
            TransitionKind.FLIP -> 19
            TransitionKind.WIPE -> 20
            TransitionKind.CINEMATIC_BARS -> 21
            TransitionKind.CROSS_ZOOM -> 22
            TransitionKind.SWIRL -> 23
            TransitionKind.PIXELATE_OUT -> 24
            TransitionKind.IRIS -> 25
            TransitionKind.CLOCK_WIPE -> 26
            TransitionKind.WHIP_PAN -> 27
            TransitionKind.HEARTBEAT -> 28
            TransitionKind.CIRCLE_OPEN -> 29
            TransitionKind.DIAGONAL_WIPE -> 30
            TransitionKind.FADE_COLOR -> 31
        }

        fun dirIndex(direction: TransitionDirection): Int = when (direction) {
            TransitionDirection.NONE -> 0
            TransitionDirection.LEFT -> 0
            TransitionDirection.RIGHT -> 1
            TransitionDirection.UP -> 2
            TransitionDirection.DOWN -> 3
        }
    }
}
