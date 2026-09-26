package com.vitacut.core.timeline

import com.vitacut.core.model.KeyframeMath
import com.vitacut.core.model.SpeedCurve
import com.vitacut.core.model.SpeedModel
import kotlin.math.max

/**
 * Time math for variable-speed clips.
 *
 * A clip selects `sourceInUs..sourceOutUs` of its asset. The speed model maps *source* progress
 * to a speed multiplier; the timeline duration is the integral of `1/speed` over the source
 * range. All mappings are computed from a piecewise-linear table with [SAMPLES] segments — exact
 * for constant speeds, and accurate to well under one frame for curves.
 */
object SpeedMath {

    private const val SAMPLES = 512

    /** Timeline duration (µs) of a source selection under [speed]. */
    fun timelineDurationUs(sourceInUs: Long, sourceOutUs: Long, speed: SpeedModel): Long {
        val sourceDuration = max(0L, sourceOutUs - sourceInUs)
        if (sourceDuration == 0L) return 0L
        return when (speed) {
            is SpeedModel.Constant -> (sourceDuration / speed.speed.coerceAtLeast(0.01f)).toLong()
            is SpeedModel.Curve -> curveTimelineDurationUs(sourceDuration, speed.curve)
        }
    }

    private fun curveTimelineDurationUs(sourceDurationUs: Long, curve: SpeedCurve): Long {
        var timeline = 0.0
        val step = sourceDurationUs.toDouble() / SAMPLES
        for (i in 0 until SAMPLES) {
            val posStart = i.toFloat() / SAMPLES
            val posEnd = (i + 1).toFloat() / SAMPLES
            val speedStart = curve.speedAt(posStart).coerceAtLeast(0.01f)
            val speedEnd = curve.speedAt(posEnd).coerceAtLeast(0.01f)
            // Trapezoidal integration of dt_timeline = ds / speed
            timeline += step / ((speedStart + speedEnd) / 2.0)
        }
        return timeline.toLong()
    }

    /**
     * Maps a timeline offset inside the clip (0..durationUs) to a source timestamp
     * (sourceInUs..sourceOutUs), honoring the speed model.
     */
    fun sourceTimeAt(
        timelineOffsetUs: Long,
        timelineDurationUs: Long,
        sourceInUs: Long,
        sourceOutUs: Long,
        speed: SpeedModel,
    ): Long {
        if (timelineDurationUs <= 0L) return sourceInUs
        val t = (timelineOffsetUs.toFloat() / timelineDurationUs).coerceIn(0f, 1f)
        return when (speed) {
            is SpeedModel.Constant ->
                sourceInUs + (t * (sourceOutUs - sourceInUs)).toLong()

            is SpeedModel.Curve -> {
                val sourceProgress = curveSourceProgressAt(t, speed.curve)
                sourceInUs + (sourceProgress * (sourceOutUs - sourceInUs)).toLong()
            }
        }
    }

    /** Inverse mapping: timeline progress (0..1) → source progress (0..1) for a curve. */
    fun curveSourceProgressAt(timelineProgress: Float, curve: SpeedCurve): Float {
        // Build cumulative timeline table over source progress, then invert.
        var cumulative = 0.0
        val table = DoubleArray(SAMPLES + 1)
        table[0] = 0.0
        for (i in 0 until SAMPLES) {
            val posStart = i.toFloat() / SAMPLES
            val posEnd = (i + 1).toFloat() / SAMPLES
            val speedStart = curve.speedAt(posStart).coerceAtLeast(0.01f)
            val speedEnd = curve.speedAt(posEnd).coerceAtLeast(0.01f)
            cumulative += (1.0 / SAMPLES) / ((speedStart + speedEnd) / 2.0)
            table[i + 1] = cumulative
        }
        val total = table[SAMPLES]
        if (total <= 0.0) return timelineProgress
        val target = timelineProgress.coerceIn(0f, 1f) * total
        // Binary search the segment containing target.
        var lo = 0
        var hi = SAMPLES
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (table[mid] <= target) lo = mid else hi = mid
        }
        val span = table[hi] - table[lo]
        val frac = if (span > 0) (target - table[lo]) / span else 0.0
        return ((lo + frac) / SAMPLES).toFloat()
    }

    /**
     * Instantaneous speed at a timeline offset — used for preview playback speed updates and for
     * building a media3 [SpeedProvider] at export time.
     */
    fun speedAtTimelineOffset(
        timelineOffsetUs: Long,
        timelineDurationUs: Long,
        sourceInUs: Long,
        sourceOutUs: Long,
        speed: SpeedModel,
    ): Float = when (speed) {
        is SpeedModel.Constant -> speed.speed
        is SpeedModel.Curve -> {
            if (timelineDurationUs <= 0L) {
                1f
            } else {
                val t = (timelineOffsetUs.toFloat() / timelineDurationUs).coerceIn(0f, 1f)
                val sourceProgress = curveSourceProgressAt(t, speed.curve)
                speed.curve.speedAt(sourceProgress).coerceIn(0.1f, 8f)
            }
        }
    }

    /** Average speed across the whole clip (UI summary, ripple math). */
    fun averageSpeed(sourceInUs: Long, sourceOutUs: Long, speed: SpeedModel): Float {
        val source = max(1L, sourceOutUs - sourceInUs)
        val timeline = max(1L, timelineDurationUs(sourceInUs, sourceOutUs, speed))
        return source.toFloat() / timeline
    }

    /** Helper: linear interpolation exposed for tests. */
    fun lerp(a: Float, b: Float, t: Float): Float = KeyframeMath.lerp(a, b, t)
}
