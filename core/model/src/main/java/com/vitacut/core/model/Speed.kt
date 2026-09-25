package com.vitacut.core.model

import kotlinx.serialization.Serializable

/** Named speed-ramp presets (plus custom). */
@Serializable
enum class SpeedCurvePreset {
    NORMAL,
    MONTAGE,
    BULLET,
    HERO,
    JUMP_CUT,
    FLASH_IN,
    FLASH_OUT,
    CUSTOM,
}

/**
 * One control point of a speed curve.
 *
 * [position] is normalized progress through the clip's *source* selection (0 = sourceIn,
 * 1 = sourceOut). [speed] is the multiplier applied at that point (0.1 .. 8).
 */
@Serializable
data class SpeedCurvePoint(
    val position: Float,
    val speed: Float,
)

/**
 * A speed curve. Evaluation is piecewise-linear between points; the timeline engine integrates
 * 1/speed to derive timeline duration and to map timeline↔source timestamps
 * (see SpeedMath).
 */
@Serializable
data class SpeedCurve(
    val points: List<SpeedCurvePoint> = listOf(SpeedCurvePoint(0f, 1f), SpeedCurvePoint(1f, 1f)),
) {
    // Note: intentionally lenient (no require) so a corrupted project file can never crash the
    // app on reopen; degenerate curves simply evaluate to 1x.

    fun speedAt(position: Float): Float {
        if (points.isEmpty()) return 1f
        val sorted = points.sortedBy { it.position }
        val p = position.coerceIn(0f, 1f)
        if (p <= sorted.first().position) return sorted.first().speed
        if (p >= sorted.last().position) return sorted.last().speed
        for (i in 0 until sorted.lastIndex) {
            val a = sorted[i]
            val b = sorted[i + 1]
            if (p in a.position..b.position) {
                val span = (b.position - a.position).coerceAtLeast(1e-6f)
                val t = (p - a.position) / span
                return KeyframeMath.lerp(a.speed, b.speed, t)
            }
        }
        return sorted.last().speed
    }

    fun withPoint(index: Int, point: SpeedCurvePoint): SpeedCurve {
        val mutable = points.toMutableList()
        if (index in mutable.indices) mutable[index] = point
        return SpeedCurve(mutable.sortedBy { it.position })
    }

    fun addPoint(point: SpeedCurvePoint): SpeedCurve =
        SpeedCurve((points + point).sortedBy { it.position })

    fun removePoint(index: Int): SpeedCurve {
        if (points.size <= 2) return this
        val clamped = index.coerceIn(1, points.size - 2) // keep first & last
        return SpeedCurve(points.filterIndexed { i, _ -> i != clamped })
    }

    companion object {
        /**
         * Preset curve shapes. Each is defined on normalized source position and yields the
         * characteristic ramp used by the preset name.
         */
        fun forPreset(preset: SpeedCurvePreset): SpeedCurve = when (preset) {
            SpeedCurvePreset.NORMAL -> SpeedCurve()
            SpeedCurvePreset.MONTAGE -> SpeedCurve(
                listOf(
                    SpeedCurvePoint(0f, 2f),
                    SpeedCurvePoint(0.35f, 2f),
                    SpeedCurvePoint(0.65f, 1f),
                    SpeedCurvePoint(1f, 1f),
                ),
            )
            SpeedCurvePreset.BULLET -> SpeedCurve(
                listOf(
                    SpeedCurvePoint(0f, 1f),
                    SpeedCurvePoint(0.3f, 1f),
                    SpeedCurvePoint(0.5f, 0.25f),
                    SpeedCurvePoint(0.7f, 1f),
                    SpeedCurvePoint(1f, 1f),
                ),
            )
            SpeedCurvePreset.HERO -> SpeedCurve(
                listOf(
                    SpeedCurvePoint(0f, 4f),
                    SpeedCurvePoint(0.45f, 1f),
                    SpeedCurvePoint(0.6f, 0.4f),
                    SpeedCurvePoint(0.8f, 1f),
                    SpeedCurvePoint(1f, 2f),
                ),
            )
            SpeedCurvePreset.JUMP_CUT -> SpeedCurve(
                listOf(
                    SpeedCurvePoint(0f, 1f),
                    SpeedCurvePoint(0.24f, 1f),
                    SpeedCurvePoint(0.26f, 3f),
                    SpeedCurvePoint(0.48f, 3f),
                    SpeedCurvePoint(0.5f, 1f),
                    SpeedCurvePoint(0.74f, 1f),
                    SpeedCurvePoint(0.76f, 3f),
                    SpeedCurvePoint(1f, 3f),
                ),
            )
            SpeedCurvePreset.FLASH_IN -> SpeedCurve(
                listOf(
                    SpeedCurvePoint(0f, 8f),
                    SpeedCurvePoint(0.3f, 2f),
                    SpeedCurvePoint(1f, 1f),
                ),
            )
            SpeedCurvePreset.FLASH_OUT -> SpeedCurve(
                listOf(
                    SpeedCurvePoint(0f, 1f),
                    SpeedCurvePoint(0.7f, 2f),
                    SpeedCurvePoint(1f, 8f),
                ),
            )
            SpeedCurvePreset.CUSTOM -> SpeedCurve()
        }
    }
}

/** Speed model of a video clip: either constant or a curve. */
@Serializable
sealed interface SpeedModel {

    @Serializable
    data class Constant(val speed: Float = 1f) : SpeedModel

    @Serializable
    data class Curve(
        val curve: SpeedCurve = SpeedCurve(),
        val preset: SpeedCurvePreset = SpeedCurvePreset.CUSTOM,
    ) : SpeedModel

    companion object {
        val NORMAL: SpeedModel = Constant(1f)

        /** The discrete speed choices offered in the speed sheet. */
        val SPEED_CHOICES = listOf(0.1f, 0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 4f, 8f)
    }
}
