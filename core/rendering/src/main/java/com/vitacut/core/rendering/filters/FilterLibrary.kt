package com.vitacut.core.rendering.filters

import com.vitacut.core.model.ColorAdjustments
import com.vitacut.core.model.CurvePoint
import com.vitacut.core.model.FilterCategory
import com.vitacut.core.model.FilterDefinition
import com.vitacut.core.model.Grating
import com.vitacut.core.model.HslAdjustments
import com.vitacut.core.model.HslBand
import com.vitacut.core.model.HslBandAdjustment
import com.vitacut.core.model.ToneCurves

/**
 * The built-in filter catalog.
 *
 * Every filter is a pure data delta over [Grading] — the exact same structure manual adjustments
 * produce — so a filter is nothing special at render time: one shared pipeline, exact previews,
 * keyframable intensity, and user presets interoperate with built-ins.
 */
object FilterLibrary {

    private fun filter(
        id: String,
        category: FilterCategory,
        adjustments: ColorAdjustments = ColorAdjustments.DEFAULT,
        curves: ToneCurves = ToneCurves.DEFAULT,
        hsl: HslAdjustments = HslAdjustments.DEFAULT,
    ) = FilterDefinition(
        id = id,
        nameKey = "filter_$id",
        category = category,
        grading = Grading(adjustments = adjustments, curves = curves, hsl = hsl),
    )

    /** S-curve helper for punchy filmic looks. */
    private fun sCurve(strength: Float) = ToneCurves(
        rgb = listOf(
            CurvePoint(0f, (0f - strength * 0.06f).coerceAtLeast(0f)),
            CurvePoint(0.25f, 0.22f - strength * 0.03f),
            CurvePoint(0.5f, 0.5f),
            CurvePoint(0.75f, 0.78f + strength * 0.03f),
            CurvePoint(1f, (1f + strength * 0.04f).coerceAtMost(1f)),
        ),
    )

    private fun hsl(vararg pairs: Pair<HslBand, HslBandAdjustment>) =
        HslAdjustments(pairs.associate { (band, adj) -> band.name to adj })

    val ALL: List<FilterDefinition> = listOf(
        // Cinematic
        filter(
            "teal-orange", FilterCategory.CINEMATIC,
            ColorAdjustments(contrast = 0.18f, saturation = 0.1f, temperature = 0.08f, highlights = -0.1f, shadows = 0.06f, fade = 0.08f),
            sCurve(1f),
            hsl(
                HslBand.CYAN to HslBandAdjustment(hueDegrees = 8f, saturation = 0.15f),
                HslBand.BLUE to HslBandAdjustment(hueDegrees = -10f, saturation = 0.1f),
                HslBand.ORANGE to HslBandAdjustment(saturation = 0.18f, luminance = 0.05f),
            ),
        ),
        filter(
            "blockbuster", FilterCategory.CINEMATIC,
            ColorAdjustments(contrast = 0.25f, saturation = 0.05f, blacks = -0.12f, sharpen = 0.15f, vignette = 0.2f),
            sCurve(1.4f),
        ),
        filter(
            "noir-soft", FilterCategory.CINEMATIC,
            ColorAdjustments(contrast = 0.2f, saturation = -0.55f, fade = 0.12f, grain = 0.15f),
            sCurve(0.8f),
        ),
        filter(
            "dream-haze", FilterCategory.CINEMATIC,
            ColorAdjustments(brightness = 0.06f, contrast = -0.08f, saturation = -0.1f, fade = 0.22f, highlights = 0.1f),
        ),

        // Portrait
        filter(
            "skin-glow", FilterCategory.PORTRAIT,
            ColorAdjustments(brightness = 0.05f, contrast = 0.04f, saturation = 0.06f, temperature = 0.05f, sharpen = 0.08f),
            hsl(HslBand.ORANGE to HslBandAdjustment(luminance = 0.08f, saturation = -0.06f)),
        ),
        filter(
            "porcelain", FilterCategory.PORTRAIT,
            ColorAdjustments(brightness = 0.08f, contrast = -0.05f, saturation = -0.12f, fade = 0.1f),
            hsl(HslBand.ORANGE to HslBandAdjustment(luminance = 0.1f)),
        ),
        filter(
            "golden-hour", FilterCategory.PORTRAIT,
            ColorAdjustments(temperature = 0.22f, brightness = 0.04f, saturation = 0.12f, highlights = -0.06f),
            hsl(HslBand.YELLOW to HslBandAdjustment(saturation = 0.15f)),
        ),

        // Travel
        filter(
            "wanderlust", FilterCategory.TRAVEL,
            ColorAdjustments(contrast = 0.14f, saturation = 0.2f, vibrance = 0.25f, temperature = 0.05f, clarity = 0.15f),
            hsl(
                HslBand.GREEN to HslBandAdjustment(hueDegrees = 10f, saturation = 0.1f),
                HslBand.CYAN to HslBandAdjustment(saturation = 0.15f),
            ),
        ),
        filter(
            "coastal", FilterCategory.TRAVEL,
            ColorAdjustments(brightness = 0.05f, saturation = 0.1f, temperature = -0.06f, highlights = 0.05f),
            hsl(HslBand.CYAN to HslBandAdjustment(luminance = 0.08f), HslBand.BLUE to HslBandAdjustment(saturation = 0.2f)),
        ),
        filter(
            "summit", FilterCategory.TRAVEL,
            ColorAdjustments(contrast = 0.18f, clarity = 0.25f, temperature = -0.1f, whites = 0.06f),
        ),

        // Food
        filter(
            "appetite", FilterCategory.FOOD,
            ColorAdjustments(saturation = 0.22f, vibrance = 0.15f, temperature = 0.12f, contrast = 0.08f, sharpen = 0.12f),
            hsl(HslBand.RED to HslBandAdjustment(saturation = 0.12f), HslBand.ORANGE to HslBandAdjustment(luminance = 0.06f)),
        ),
        filter(
            "cafe-light", FilterCategory.FOOD,
            ColorAdjustments(brightness = 0.08f, temperature = 0.15f, fade = 0.1f, saturation = -0.04f),
        ),

        // Vintage
        filter(
            "super8", FilterCategory.VINTAGE,
            ColorAdjustments(contrast = 0.1f, saturation = -0.2f, temperature = 0.15f, fade = 0.25f, grain = 0.35f, vignette = 0.3f),
            ToneCurves(
                rgb = listOf(CurvePoint(0f, 0.08f), CurvePoint(0.5f, 0.52f), CurvePoint(1f, 0.94f)),
                red = listOf(CurvePoint(0f, 0.1f), CurvePoint(1f, 1f)),
                blue = listOf(CurvePoint(0f, 0.02f), CurvePoint(1f, 0.88f)),
            ),
        ),
        filter(
            "polaroid", FilterCategory.VINTAGE,
            ColorAdjustments(fade = 0.18f, temperature = 0.08f, contrast = -0.05f, saturation = -0.1f, vignette = 0.12f),
            ToneCurves(rgb = listOf(CurvePoint(0f, 0.06f), CurvePoint(0.5f, 0.5f), CurvePoint(1f, 0.96f))),
        ),
        filter(
            "sepia-days", FilterCategory.VINTAGE,
            ColorAdjustments(saturation = -0.6f, temperature = 0.3f, contrast = 0.08f, grain = 0.18f),
        ),

        // Black & White
        filter(
            "mono-contrast", FilterCategory.BLACK_WHITE,
            ColorAdjustments(saturation = -1f, contrast = 0.3f, blacks = -0.1f, sharpen = 0.15f),
            sCurve(1.2f),
        ),
        filter(
            "silver", FilterCategory.BLACK_WHITE,
            ColorAdjustments(saturation = -1f, contrast = 0.1f, fade = 0.15f, grain = 0.1f),
        ),
        filter(
            "carbon", FilterCategory.BLACK_WHITE,
            ColorAdjustments(saturation = -1f, contrast = 0.22f, vignette = 0.35f, clarity = 0.2f),
        ),

        // Warm
        filter(
            "ember", FilterCategory.WARM,
            ColorAdjustments(temperature = 0.25f, brightness = 0.03f, saturation = 0.1f, highlights = -0.05f),
        ),
        filter(
            "sunset-drive", FilterCategory.WARM,
            ColorAdjustments(temperature = 0.18f, tint = 0.06f, saturation = 0.15f, fade = 0.08f),
            hsl(HslBand.MAGENTA to HslBandAdjustment(saturation = 0.15f)),
        ),

        // Cool
        filter(
            "arctic", FilterCategory.COOL,
            ColorAdjustments(temperature = -0.22f, brightness = 0.05f, saturation = -0.05f, whites = 0.05f),
        ),
        filter(
            "midnight", FilterCategory.COOL,
            ColorAdjustments(temperature = -0.15f, tint = -0.05f, contrast = 0.15f, blacks = -0.15f, vignette = 0.25f),
            hsl(HslBand.BLUE to HslBandAdjustment(saturation = 0.1f, luminance = -0.05f)),
        ),

        // Retro
        filter(
            "vhs-night", FilterCategory.RETRO,
            ColorAdjustments(saturation = 0.2f, contrast = 0.1f, temperature = -0.08f, grain = 0.25f),
            hsl(HslBand.PURPLE to HslBandAdjustment(saturation = 0.2f), HslBand.MAGENTA to HslBandAdjustment(hueDegrees = -12f)),
        ),
        filter(
            "arcade", FilterCategory.RETRO,
            ColorAdjustments(saturation = 0.35f, contrast = 0.15f, vibrance = 0.2f),
        ),
        filter(
            "kodachrome", FilterCategory.RETRO,
            ColorAdjustments(saturation = 0.12f, contrast = 0.12f, temperature = 0.06f, blacks = 0.05f),
            ToneCurves(
                red = listOf(CurvePoint(0f, 0.02f), CurvePoint(1f, 0.98f)),
                green = listOf(CurvePoint(0f, 0.01f), CurvePoint(1f, 1f)),
                blue = listOf(CurvePoint(0f, 0.06f), CurvePoint(1f, 0.95f)),
            ),
        ),

        // Social
        filter(
            "creator-pop", FilterCategory.SOCIAL,
            ColorAdjustments(brightness = 0.05f, contrast = 0.12f, saturation = 0.18f, vibrance = 0.2f, sharpen = 0.2f),
        ),
        filter(
            "clean-feed", FilterCategory.SOCIAL,
            ColorAdjustments(brightness = 0.06f, contrast = 0.05f, saturation = 0.05f, temperature = 0.03f),
        ),
        filter(
            "moody-reel", FilterCategory.SOCIAL,
            ColorAdjustments(contrast = 0.2f, saturation = -0.15f, temperature = -0.1f, blacks = -0.1f, vignette = 0.15f, grain = 0.1f),
        ),
    )

    private val byId = ALL.associateBy { it.id }

    fun byId(id: String?): FilterDefinition? = id?.let { byId[it] }

    fun byCategory(category: FilterCategory): List<FilterDefinition> =
        ALL.filter { it.category == category }

    /**
     * Combines a filter's grading delta with the clip's manual grading at [intensity] (0..1).
     * Manual adjustments always apply at full strength; the filter contributes scaled by intensity.
     */
    fun blend(base: Grading, filterId: String?, intensity: Float): Grading {
        val definition = byId(filterId) ?: return base
        if (intensity <= 0f) return base
        val f = definition.grading
        val b = base.adjustments
        val t = intensity.coerceIn(0f, 1f)
        return base.copy(
            adjustments = ColorAdjustments(
                brightness = b.brightness + f.adjustments.brightness * t,
                exposure = b.exposure + f.adjustments.exposure * t,
                contrast = blendUnit(b.contrast, f.adjustments.contrast, t),
                saturation = blendUnit(b.saturation, f.adjustments.saturation, t),
                vibrance = blendUnit(b.vibrance, f.adjustments.vibrance, t),
                temperature = blendUnit(b.temperature, f.adjustments.temperature, t),
                tint = blendUnit(b.tint, f.adjustments.tint, t),
                highlights = blendUnit(b.highlights, f.adjustments.highlights, t),
                shadows = blendUnit(b.shadows, f.adjustments.shadows, t),
                whites = blendUnit(b.whites, f.adjustments.whites, t),
                blacks = blendUnit(b.blacks, f.adjustments.blacks, t),
                fade = (b.fade + f.adjustments.fade * t).coerceIn(0f, 1f),
                sharpen = (b.sharpen + f.adjustments.sharpen * t).coerceIn(0f, 1f),
                clarity = blendUnit(b.clarity, f.adjustments.clarity, t),
                vignette = (b.vignette + f.adjustments.vignette * t).coerceIn(0f, 1f),
                grain = (b.grain + f.adjustments.grain * t).coerceIn(0f, 1f),
            ),
            curves = if (t >= 0.99f && !f.curves.isIdentity) f.curves else base.curves,
            hsl = mergeHsl(base.hsl, f.hsl, t),
            lutAssetName = base.lutAssetName ?: f.lutAssetName,
        )
    }

    private fun blendUnit(base: Float, delta: Float, t: Float): Float =
        (base + delta * t).coerceIn(-1f, 1f)

    private fun mergeHsl(base: HslAdjustments, filter: HslAdjustments, t: Float): HslAdjustments {
        if (filter.isNeutral) return base
        val merged = HslBand.entries.associate { band ->
            val b = base.forBand(band)
            val f = filter.forBand(band)
            band.name to HslBandAdjustment(
                hueDegrees = b.hueDegrees + f.hueDegrees * t,
                saturation = (b.saturation + f.saturation * t).coerceIn(-1f, 1f),
                luminance = (b.luminance + f.luminance * t).coerceIn(-1f, 1f),
            )
        }.filterValues { !it.isNeutral }
        return HslAdjustments(merged)
    }
}
