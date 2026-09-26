package com.vitacut.core.rendering.pipeline

import androidx.media3.common.Effect
import com.vitacut.core.model.AudioClipItem
import com.vitacut.core.model.KeyframeMath
import com.vitacut.core.model.KeyframeProperty
import com.vitacut.core.model.Project
import com.vitacut.core.model.SpeedModel
import com.vitacut.core.model.VideoClipItem
import com.vitacut.core.rendering.audio.AudioEffectsFactory
import com.vitacut.core.rendering.effects.ChromaKeyEffect
import com.vitacut.core.rendering.effects.ClipPlacementEffect
import com.vitacut.core.rendering.effects.ColorGradeEffect
import com.vitacut.core.rendering.effects.CurvesEffect
import com.vitacut.core.rendering.effects.FxEffect
import com.vitacut.core.rendering.effects.HslBandsEffect
import com.vitacut.core.rendering.effects.LutEffect
import com.vitacut.core.rendering.effects.MaskEffect
import com.vitacut.core.rendering.effects.OverlayCompositeEffect
import com.vitacut.core.rendering.effects.TransitionEffect
import com.vitacut.core.rendering.filters.FilterLibrary
import com.vitacut.core.rendering.overlay.OverlayComposer
import com.vitacut.core.timeline.SpeedMath
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the Media3 effect chain for one timeline clip.
 *
 * **This single builder is the contract that keeps preview and export identical.** Both the
 * preview player (`ExoPlayer.setVideoEffects`) and the export graph (`EditedMediaItem.effects`)
 * receive chains from here, built from the same immutable [Project] snapshot. The pipeline is:
 *
 * ```
 * Media Source → Decoder → ChromaKey → FX shaders → Color grade → Curves → HSL → LUT →
 * Mask → Clip placement (crop/fit/transform/canvas background) → Transition edges →
 * Overlays (images, captions, text, stickers) → Compositor → Encoder/Surface
 * ```
 *
 * Time base: every lambda receives the **clip-local** presentation time (0 at the clip's first
 * frame), which is what both ExoPlayer item effects and Transformer item effects deliver.
 * Keyframes (stored item-relative) therefore evaluate without any conversion, and overlay
 * providers convert to absolute timeline time internally.
 */
@Singleton
class EffectsPipelineBuilder @Inject constructor(
    private val overlayComposer: OverlayComposer,
) {

    /** Optional resolver for user LUT assets (file name → bitmap strip + size). */
    fun interface LutResolver {
        fun resolve(lutAssetName: String): LutTexture?
    }

    data class LutTexture(val bitmapProvider: () -> android.graphics.Bitmap?, val size: Int)

    /**
     * Video effects for [clip] inside [project].
     *
     * @param speedApplied when true (export), speed retiming is handled by the audio/video
     *   speed-changing effect pair and frame timestamps are already clip-local post-speed.
     *   When false (preview), the player itself applies [androidx.media3.exoplayer.ExoPlayer.setPlaybackSpeed],
     *   and keyframe times still align because the player reports item positions.
     */
    fun videoEffectsFor(
        project: Project,
        clip: VideoClipItem,
        imageFrameSource: OverlayComposer.ImageFrameSource? = null,
        lutResolver: LutResolver? = null,
    ): List<Effect> {
        val effects = mutableListOf<Effect>()
        val asset = project.asset(clip.assetId)

        // Keyframed grading/opacity evaluation helpers (clip-local time).
        fun adjusted(base: Float, property: KeyframeProperty, timeUs: Long): Float =
            clip.keyframes.trackFor(property)?.let { KeyframeMath.evaluate(it.keyframes, timeUs) } ?: base

        // 1. Chroma key.
        if (clip.chromaKey.enabled) {
            effects += ChromaKeyEffect { clip.chromaKey }
        }

        // 2. Stylized effects, in the user's layer order.
        for (instance in clip.enabledEffects()) {
            val instanceId = instance.id
            val keyframes = clip.effectKeyframes.firstOrNull { it.effectId == instanceId }
            effects += FxEffect(instance.kind) { timeUs ->
                keyframes?.let { KeyframeMath.evaluate(it.keyframes, timeUs) } ?: instance.intensity
            }
        }

        // 3. Color grading (filter blended with manual adjustments, both keyframable).
        val filterIntensityTrack = clip.keyframes.trackFor(KeyframeProperty.FILTER_INTENSITY)
        effects += ColorGradeEffect { timeUs ->
            val filterIntensity = filterIntensityTrack?.let { KeyframeMath.evaluate(it.keyframes, timeUs) }
                ?: clip.filter.intensity
            val blended = FilterLibrary.blend(clip.grading, clip.filter.filterId, filterIntensity)
            blended.adjustments.copy(
                brightness = adjusted(blended.adjustments.brightness, KeyframeProperty.BRIGHTNESS, timeUs),
                exposure = adjusted(blended.adjustments.exposure, KeyframeProperty.EXPOSURE, timeUs),
                contrast = adjusted(blended.adjustments.contrast, KeyframeProperty.CONTRAST, timeUs),
                saturation = adjusted(blended.adjustments.saturation, KeyframeProperty.SATURATION, timeUs),
                vibrance = adjusted(blended.adjustments.vibrance, KeyframeProperty.VIBRANCE, timeUs),
                temperature = adjusted(blended.adjustments.temperature, KeyframeProperty.TEMPERATURE, timeUs),
                tint = adjusted(blended.adjustments.tint, KeyframeProperty.TINT, timeUs),
                highlights = adjusted(blended.adjustments.highlights, KeyframeProperty.HIGHLIGHTS, timeUs),
                shadows = adjusted(blended.adjustments.shadows, KeyframeProperty.SHADOWS, timeUs),
                whites = adjusted(blended.adjustments.whites, KeyframeProperty.WHITES, timeUs),
                blacks = adjusted(blended.adjustments.blacks, KeyframeProperty.BLACKS, timeUs),
                fade = adjusted(blended.adjustments.fade, KeyframeProperty.FADE, timeUs),
                sharpen = adjusted(blended.adjustments.sharpen, KeyframeProperty.SHARPEN, timeUs),
                clarity = adjusted(blended.adjustments.clarity, KeyframeProperty.CLARITY, timeUs),
                vignette = adjusted(blended.adjustments.vignette, KeyframeProperty.VIGNETTE, timeUs),
                grain = adjusted(blended.adjustments.grain, KeyframeProperty.GRAIN, timeUs),
            )
        }

        // 4. Tone curves.
        val blendedBase = FilterLibrary.blend(clip.grading, clip.filter.filterId, clip.filter.intensity)
        if (!blendedBase.curves.isIdentity) {
            effects += CurvesEffect(
                curvesAt = { blendedBase.curves },
                intensityAt = { timeUs ->
                    filterIntensityTrack?.let { KeyframeMath.evaluate(it.keyframes, timeUs) } ?: 1f
                },
            )
        }

        // 5. HSL bands.
        if (!blendedBase.hsl.isNeutral) {
            effects += HslBandsEffect { blendedBase.hsl }
        }

        // 6. Optional 3D LUT.
        val lutName = blendedBase.lutAssetName
        if (lutName != null && lutResolver != null) {
            val lut = lutResolver.resolve(lutName)
            if (lut != null) {
                effects += LutEffect(
                    lutBitmapProvider = lut.bitmapProvider,
                    lutSizeProvider = { lut.size },
                )
            }
        }

        // 7. Mask.
        if (clip.mask.isActive) {
            effects += MaskEffect({ timeUs ->
                // Keyframed mask parameters.
                val mask = clip.mask
                mask.copy(
                    centerX = adjusted(mask.centerX, KeyframeProperty.MASK_CENTER_X, timeUs),
                    centerY = adjusted(mask.centerY, KeyframeProperty.MASK_CENTER_Y, timeUs),
                    scaleX = adjusted(mask.scaleX, KeyframeProperty.MASK_SCALE, timeUs),
                    scaleY = adjusted(mask.scaleY, KeyframeProperty.MASK_SCALE, timeUs),
                    rotationDegrees = adjusted(mask.rotationDegrees, KeyframeProperty.MASK_ROTATION, timeUs),
                    feather = adjusted(mask.feather, KeyframeProperty.MASK_FEATHER, timeUs),
                )
            })
        }

        // 8. Clip placement → canvas (crop, fit, transform, opacity, canvas background).
        val sourceWidth = asset?.displayWidth?.takeIf { it > 0 } ?: project.canvas.width
        val sourceHeight = asset?.displayHeight?.takeIf { it > 0 } ?: project.canvas.height
        effects += ClipPlacementEffect(
            canvasWidth = project.canvas.width,
            canvasHeight = project.canvas.height,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            contentFit = clip.contentFit,
            crop = clip.crop,
            background = project.canvas.background,
            transformAt = { timeUs ->
                clip.transform.copy(
                    translationX = adjusted(clip.transform.translationX, KeyframeProperty.POSITION_X, timeUs),
                    translationY = adjusted(clip.transform.translationY, KeyframeProperty.POSITION_Y, timeUs),
                    scaleX = adjusted(clip.transform.scaleX, KeyframeProperty.SCALE_X, timeUs),
                    scaleY = adjusted(clip.transform.scaleY, KeyframeProperty.SCALE_Y, timeUs),
                    rotationDegrees = adjusted(clip.transform.rotationDegrees, KeyframeProperty.ROTATION, timeUs),
                    opacity = adjusted(clip.transform.opacity, KeyframeProperty.OPACITY, timeUs),
                )
            },
        )

        // 9. Transition edges.
        clip.transitionIn?.takeIf { it.durationUs > 0 }?.let {
            effects += TransitionEffect(it.copy(atEnd = false), clip.durationUs)
        }
        clip.transitionOut?.takeIf { it.durationUs > 0 }?.let {
            effects += TransitionEffect(it.copy(atEnd = true), clip.durationUs)
        }

        // 10. Overlay layers (images, captions, text, stickers) in z-order.
        val overlayLayers = overlayComposer.layersForWindow(
            project = project,
            windowStartUs = clip.timelineStartUs,
            windowEndUs = clip.timelineEndUs,
            imageFrameSource = imageFrameSource,
        )
        for (layer in overlayLayers) {
            effects += OverlayCompositeEffect(layer.provider)
        }

        return effects
    }

    /**
     * Video effects for a *blank* window (no clip playing) so overlays and captions still render
     * over the canvas background during gaps. Used by the export composition (black filler
     * items) and preview gap handling.
     */
    fun videoEffectsForBlankWindow(
        project: Project,
        windowStartUs: Long,
        windowEndUs: Long,
        imageFrameSource: OverlayComposer.ImageFrameSource? = null,
    ): List<Effect> {
        val effects = mutableListOf<Effect>()
        // Paint the canvas background via a placement effect over the black filler frame.
        effects += ClipPlacementEffect(
            canvasWidth = project.canvas.width,
            canvasHeight = project.canvas.height,
            sourceWidth = project.canvas.width,
            sourceHeight = project.canvas.height,
            contentFit = com.vitacut.core.model.ContentFit.COVER,
            crop = com.vitacut.core.model.CropSettings.FULL,
            background = project.canvas.background,
            transformAt = { com.vitacut.core.model.SpatialTransform.DEFAULT },
        )
        val overlayLayers = overlayComposer.layersForWindow(
            project, windowStartUs, windowEndUs, imageFrameSource,
        )
        for (layer in overlayLayers) {
            effects += OverlayCompositeEffect(layer.provider)
        }
        return effects
    }

    /** Audio processors for a video clip's own audio. */
    fun audioEffectsFor(clip: VideoClipItem): List<androidx.media3.common.audio.AudioProcessor> =
        AudioEffectsFactory.forVideoClip(clip) { timeUs ->
            clip.keyframes.trackFor(KeyframeProperty.VOLUME)
                ?.let { KeyframeMath.evaluate(it.keyframes, timeUs) } ?: 1f
        }

    /** Audio processors + speed provider inputs for a music/voiceover clip. */
    fun audioEffectsFor(clip: AudioClipItem): List<androidx.media3.common.audio.AudioProcessor> =
        AudioEffectsFactory.forAudioClip(clip) { timeUs ->
            clip.keyframes.trackFor(KeyframeProperty.VOLUME)
                ?.let { KeyframeMath.evaluate(it.keyframes, timeUs) } ?: 1f
        }

    /** Whether the clip needs the experimental speed-changing effect pair (curves or ≠1x). */
    fun needsSpeedEffect(speed: SpeedModel): Boolean = when (speed) {
        is SpeedModel.Constant -> kotlin.math.abs(speed.speed - 1f) > 0.001f
        is SpeedModel.Curve -> true
    }

    /** Clip-local timeline duration under the speed model (source selection / speed). */
    fun timelineDurationOf(clip: VideoClipItem): Long =
        SpeedMath.timelineDurationUs(clip.sourceInUs, clip.sourceOutUs, clip.speed)
}
