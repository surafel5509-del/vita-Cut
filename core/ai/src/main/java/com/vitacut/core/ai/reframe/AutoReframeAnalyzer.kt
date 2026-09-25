package com.vitacut.core.ai.reframe

import com.vitacut.core.model.TrackPath
import com.vitacut.core.model.TrackPoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Auto-reframe math: given subject-interest observations over time (from ML Kit object
 * detection, subject segmentation, or manual taps) and a target aspect ratio, produce a smooth
 * [TrackPath] that keeps the subject inside the reframed crop window.
 *
 * Pure Kotlin → unit-testable; the feature layer binds the resulting path to the main clip's
 * transform (keyframed pan/zoom) through the standard keyframe engine, so it animates in both
 * preview and export.
 *
 * Coordinate conventions: all positions are normalized frame fractions (0..1, origin top-left).
 * [ReframeResult.scale] is the zoom needed to fill the target aspect from the source frame;
 * track points carry the crop-window center.
 */
object AutoReframeAnalyzer {

    /** One observation of where the interesting content is at [timeUs]. */
    data class SubjectFrame(
        val timeUs: Long,
        val centerX: Float,
        val centerY: Float,
        /** Subject box size as a fraction of frame width/height (for zoom-to-fit). */
        val widthFraction: Float = 0f,
        val heightFraction: Float = 0f,
        val confidence: Float = 1f,
    )

    data class Config(
        /** Moving-average window for center smoothing, in observations. */
        val smoothingWindow: Int = 5,
        /** Maximum center travel per second (fraction/sec) — kills pan jitter. */
        val maxPanSpeed: Float = 0.6f,
        /** Extra zoom beyond the minimum crop fill so subjects breathe. */
        val zoomMargin: Float = 1.0f,
        /** Maximum observations in the output path (keyframe budget). */
        val maxPathPoints: Int = 60,
    )

    data class ReframeResult(
        val path: TrackPath,
        /** Crop window size as fractions of the source frame. */
        val cropWidthFraction: Float,
        val cropHeightFraction: Float,
        /** Zoom factor (1/crop fraction on the constrained axis) to fill the canvas. */
        val scale: Float,
        val targetAspect: Float,
    )

    /**
     * @param subjects time-ordered observations (empty → centered static frame)
     * @param sourceAspect width/height of the source media
     * @param targetAspect width/height of the desired output (16:9, 9:16, 1:1, 4:5…)
     */
    fun analyze(
        subjects: List<SubjectFrame>,
        sourceAspect: Float,
        targetAspect: Float,
        durationUs: Long,
        config: Config = Config(),
    ): ReframeResult {
        require(sourceAspect > 0f && targetAspect > 0f) { "aspects must be positive" }

        // Largest in-frame window with the target aspect.
        val cropWidthFraction: Float
        val cropHeightFraction: Float
        if (targetAspect < sourceAspect) {
            // Target is narrower → crop the sides, pan horizontally.
            cropHeightFraction = 1f
            cropWidthFraction = targetAspect / sourceAspect
        } else {
            // Target is wider → crop top/bottom, pan vertically.
            cropWidthFraction = 1f
            cropHeightFraction = sourceAspect / targetAspect
        }
        val scale = 1f / min(cropWidthFraction, cropHeightFraction)

        val points = if (subjects.isEmpty()) {
            listOf(TrackPoint(timeUs = 0L, x = 0.5f, y = 0.5f, scale = scale))
        } else {
            smoothedPath(subjects, cropWidthFraction, cropHeightFraction, durationUs, config, scale)
        }

        return ReframeResult(
            path = TrackPath(
                points = points,
                regionSizeFraction = min(cropWidthFraction, cropHeightFraction),
            ),
            cropWidthFraction = cropWidthFraction,
            cropHeightFraction = cropHeightFraction,
            scale = scale,
            targetAspect = targetAspect,
        )
    }

    private fun smoothedPath(
        subjects: List<SubjectFrame>,
        cropW: Float,
        cropH: Float,
        durationUs: Long,
        config: Config,
        scale: Float,
    ): List<TrackPoint> {
        val usable = subjects.filter { it.confidence > 0.2f }.ifEmpty { subjects }
        // Moving average over the smoothing window.
        val window = max(1, config.smoothingWindow)
        val smoothedX = movingAverage(usable.map { it.centerX.toDouble() }, window)
        val smoothedY = movingAverage(usable.map { it.centerY.toDouble() }, window)

        // Rate-limit panning and clamp so the crop window never leaves the frame.
        val halfW = cropW / 2f
        val halfH = cropH / 2f
        val points = mutableListOf<TrackPoint>()
        var prevX = smoothedX.first().coerceIn(halfW, 1f - halfW).toFloat()
        var prevY = smoothedY.first().coerceIn(halfH, 1f - halfH).toFloat()
        var prevT = usable.first().timeUs

        for (i in usable.indices) {
            val t = usable[i].timeUs
            val dtSec = max(0L, t - prevT) / 1_000_000f
            val maxTravel = config.maxPanSpeed * dtSec
            val targetX = smoothedX[i].toFloat().coerceIn(halfW, 1f - halfW)
            val targetY = smoothedY[i].toFloat().coerceIn(halfH, 1f - halfH)
            val x = approach(prevX, targetX, maxTravel)
            val y = approach(prevY, targetY, maxTravel)
            points += TrackPoint(timeUs = t, x = x, y = y, scale = scale)
            prevX = x
            prevY = y
            prevT = t
        }
        // Extend the path to the timeline end so playback past the last detection stays put.
        if (durationUs > points.last().timeUs) {
            points += TrackPoint(timeUs = durationUs, x = prevX, y = prevY, scale = scale)
        }
        return decimate(points, config.maxPathPoints)
    }

    private fun approach(current: Float, target: Float, maxTravel: Float): Float {
        val delta = target - current
        return if (abs(delta) <= maxTravel) target else current + maxTravel * kotlin.math.signum(delta)
    }

    private fun movingAverage(values: List<Double>, window: Int): List<Double> =
        values.mapIndexed { i, _ ->
            val from = max(0, i - window / 2)
            val to = min(values.size - 1, i + window / 2)
            (from..to).sumOf { values[it] } / (to - from + 1)
        }

    /** Keeps first/last and evenly samples the middle so the keyframe budget is respected. */
    private fun decimate(points: List<TrackPoint>, maxPoints: Int): List<TrackPoint> {
        if (points.size <= maxPoints) return points
        val step = (points.size - 1).toFloat() / (maxPoints - 1)
        val out = (0 until maxPoints).map { points[(it * step).toInt().coerceAtMost(points.lastIndex)] }
        return out.distinctBy { it.timeUs }
    }
}
