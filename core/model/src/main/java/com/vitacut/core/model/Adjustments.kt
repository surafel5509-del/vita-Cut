package com.vitacut.core.model

import kotlinx.serialization.Serializable

/**
 * Color grading parameters. All values are normalized:
 *
 * - Neutral = 0 unless documented otherwise.
 * - Range is [-1, 1] for symmetric adjustments, [0, 1] for additive ones (fade, grain…).
 *
 * The rendering layer maps these to a combined GLSL grade shader + an [RgbMatrix]-style linear
 * transform so that preview and export produce identical results.
 */
@Serializable
data class ColorAdjustments(
    /** -1..1, additive lift of luma. */
    val brightness: Float = 0f,
    /** -1..1, exposure in normalized stops (~±2 stops). */
    val exposure: Float = 0f,
    /** -1..1 */
    val contrast: Float = 0f,
    /** -1..1 */
    val saturation: Float = 0f,
    /** -1..1, saturation biased toward already-saturated colors. */
    val vibrance: Float = 0f,
    /** -1..1, cool→warm. */
    val temperature: Float = 0f,
    /** -1..1, green→magenta. */
    val tint: Float = 0f,
    /** -1..1 */
    val highlights: Float = 0f,
    /** -1..1 */
    val shadows: Float = 0f,
    /** -1..1 */
    val whites: Float = 0f,
    /** -1..1 */
    val blacks: Float = 0f,
    /** 0..1, lifts blacks / lowers whites for a faded film look. */
    val fade: Float = 0f,
    /** 0..1 */
    val sharpen: Float = 0f,
    /** -1..1, local contrast (clarity). */
    val clarity: Float = 0f,
    /** 0..1 */
    val vignette: Float = 0f,
    /** 0..1 */
    val grain: Float = 0f,
) {
    val isNeutral: Boolean get() = this == DEFAULT

    companion object {
        val DEFAULT = ColorAdjustments()
    }
}

/** A single control point of a tone curve, both coordinates in 0..1. */
@Serializable
data class CurvePoint(
    val x: Float,
    val y: Float,
)

/** Channel a tone curve applies to. */
@Serializable
enum class CurveChannel { RGB, RED, GREEN, BLUE }

/**
 * RGB tone curves. Each channel holds an increasing list of control points; the renderer bakes
 * them into a 256-entry LUT texture per channel (identity curve when only (0,0) and (1,1)).
 */
@Serializable
data class ToneCurves(
    val rgb: List<CurvePoint> = IDENTITY_POINTS,
    val red: List<CurvePoint> = IDENTITY_POINTS,
    val green: List<CurvePoint> = IDENTITY_POINTS,
    val blue: List<CurvePoint> = IDENTITY_POINTS,
) {
    val isIdentity: Boolean
        get() = rgb == IDENTITY_POINTS && red == IDENTITY_POINTS &&
            green == IDENTITY_POINTS && blue == IDENTITY_POINTS

    fun pointsFor(channel: CurveChannel): List<CurvePoint> = when (channel) {
        CurveChannel.RGB -> rgb
        CurveChannel.RED -> red
        CurveChannel.GREEN -> green
        CurveChannel.BLUE -> blue
    }

    companion object {
        val IDENTITY_POINTS = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))
        val DEFAULT = ToneCurves()

        /**
         * Evaluates a monotonic piecewise-cubic (Catmull-Rom style, clamped) curve at [x].
         * Shared by the LUT baker and the curves editor preview.
         */
        fun evaluate(points: List<CurvePoint>, x: Float): Float {
            val sorted = points.sortedBy { it.x }
            if (sorted.isEmpty()) return x
            if (sorted.size == 1) return sorted[0].y
            if (x <= sorted.first().x) return sorted.first().y
            if (x >= sorted.last().x) return sorted.last().y
            for (i in 0 until sorted.lastIndex) {
                val a = sorted[i]
                val b = sorted[i + 1]
                if (x in a.x..b.x) {
                    val span = (b.x - a.x).coerceAtLeast(1e-6f)
                    val t = (x - a.x) / span
                    return KeyframeMath.lerp(a.y, b.y, KeyframeMath.smoothStep(t))
                }
            }
            return x
        }
    }
}

/** HSL hue bands. */
@Serializable
enum class HslBand { RED, ORANGE, YELLOW, GREEN, CYAN, BLUE, PURPLE, MAGENTA }

/** Per-band HSL adjustment; hue in degrees (-180..180), saturation/luminance -1..1. */
@Serializable
data class HslBandAdjustment(
    val hueDegrees: Float = 0f,
    val saturation: Float = 0f,
    val luminance: Float = 0f,
) {
    val isNeutral: Boolean get() = hueDegrees == 0f && saturation == 0f && luminance == 0f
}

@Serializable
data class HslAdjustments(
    val bands: Map<String, HslBandAdjustment> = emptyMap(),
) {
    fun forBand(band: HslBand): HslBandAdjustment =
        bands[band.name] ?: HslBandAdjustment()

    fun withBand(band: HslBand, adjustment: HslBandAdjustment): HslAdjustments =
        if (adjustment.isNeutral) copy(bands = bands - band.name)
        else copy(bands = bands + (band.name to adjustment))

    val isNeutral: Boolean get() = bands.values.all { it.isNeutral }

    companion object {
        val DEFAULT = HslAdjustments()
    }
}

/**
 * The complete grading stack of a clip: sliders, curves and HSL. A filter preset is simply a
 * named [ColorAdjustments] delta (see [FilterLibrary]).
 */
@Serializable
data class Grading(
    val adjustments: ColorAdjustments = ColorAdjustments.DEFAULT,
    val curves: ToneCurves = ToneCurves.DEFAULT,
    val hsl: HslAdjustments = HslAdjustments.DEFAULT,
    /** Optional 3D LUT applied last, referenced by a bundled/user-provided file name. */
    val lutAssetName: String? = null,
) {
    val isNeutral: Boolean
        get() = adjustments.isNeutral && curves.isIdentity && hsl.isNeutral && lutAssetName == null

    companion object {
        val DEFAULT = Grading()
    }
}

/** A user-saved grading preset. */
@Serializable
data class GradingPreset(
    val id: String = newId(),
    val name: String,
    val grading: Grading,
    val createdAtMs: Long = 0L,
)
