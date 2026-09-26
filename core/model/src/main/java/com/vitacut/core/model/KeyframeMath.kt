package com.vitacut.core.model

import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.PI

/**
 * Evaluation & editing math for keyframe tracks. Pure functions so they are trivially unit
 * testable and can be reused by the preview renderer, the export pipeline and the curve editors.
 */
object KeyframeMath {

    /**
     * Evaluates [keyframes] (assumed sorted by time) at [timeUs].
     *
     * - Before the first keyframe → first value.
     * - After the last keyframe → last value.
     * - Exactly one keyframe → constant value.
     */
    fun evaluate(keyframes: List<Keyframe>, timeUs: Long): Float {
        if (keyframes.isEmpty()) return 0f
        if (keyframes.size == 1) return keyframes[0].value
        if (timeUs <= keyframes.first().timeUs) return keyframes.first().value
        if (timeUs >= keyframes.last().timeUs) return keyframes.last().value

        var lo = 0
        var hi = keyframes.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (keyframes[mid].timeUs <= timeUs) lo = mid else hi = mid
        }
        val a = keyframes[lo]
        val b = keyframes[hi]
        val span = (b.timeUs - a.timeUs).coerceAtLeast(1L)
        val t = ((timeUs - a.timeUs).toFloat() / span).coerceIn(0f, 1f)
        return lerp(a.value, b.value, ease(t, a.interpolation))
    }

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** Normalized easing curves (input and output in 0..1). */
    fun ease(t: Float, interpolation: Interpolation): Float = when (interpolation) {
        Interpolation.LINEAR -> t
        Interpolation.EASE_IN -> t.pow(2f)
        Interpolation.EASE_OUT -> 1f - (1f - t).pow(2f)
        Interpolation.EASE_IN_OUT -> smoothStep(t)
        Interpolation.HOLD -> 0f
    }

    /** Classic smootherstep-like cubic. */
    fun smoothStep(t: Float): Float = t * t * (3f - 2f * t)

    /** Sinusoidal ease used by looping animations. */
    fun sinePulse(t: Float): Float = ((sin((t * 2.0 * PI).toFloat()) + 1f) / 2f)

    /** Inserts or replaces the keyframe at the same timestamp; returns a sorted list. */
    fun upsert(keyframes: List<Keyframe>, keyframe: Keyframe): List<Keyframe> =
        (keyframes.filterNot { it.timeUs == keyframe.timeUs } + keyframe)
            .sortedBy { it.timeUs }

    /** Moves the keyframe nearest [originalTimeUs] to [newTimeUs] without crossing neighbors. */
    fun move(
        keyframes: List<Keyframe>,
        originalTimeUs: Long,
        newTimeUs: Long,
    ): List<Keyframe> {
        val index = keyframes.indexOfFirst { it.timeUs == originalTimeUs }
        if (index < 0) return keyframes
        val min = if (index == 0) Long.MIN_VALUE else keyframes[index - 1].timeUs + 1
        val max = if (index == keyframes.lastIndex) Long.MAX_VALUE else keyframes[index + 1].timeUs - 1
        val clamped = newTimeUs.coerceIn(maxOf(min, 0L), maxOf(max, 0L))
        return keyframes.toMutableList().apply { set(index, get(index).copy(timeUs = clamped)) }
    }

    fun remove(keyframes: List<Keyframe>, timeUs: Long): List<Keyframe> =
        keyframes.filterNot { it.timeUs == timeUs }

    /** Index of the keyframe at or before [timeUs], or -1. */
    fun previousIndex(keyframes: List<Keyframe>, timeUs: Long): Int {
        var result = -1
        for (i in keyframes.indices) {
            if (keyframes[i].timeUs <= timeUs) result = i else break
        }
        return result
    }

    /** Index of the first keyframe after [timeUs], or -1. */
    fun nextIndex(keyframes: List<Keyframe>, timeUs: Long): Int =
        keyframes.indexOfFirst { it.timeUs > timeUs }

    fun packColor(argb: Int): Float = Float.fromBits(argb)
    fun unpackColor(bits: Float): Int = java.lang.Float.floatToIntBits(bits)
}
