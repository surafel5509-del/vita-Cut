package com.vitacut.core.model

import kotlinx.serialization.Serializable

/**
 * Common contract of everything that can sit on a track.
 *
 * All items carry a stable [id], a timeline start, a duration, a layer order within their track
 * and a locked flag. Concrete types add domain-specific state. Sealed + @Serializable gives
 * polymorphic project JSON for free.
 */
@Serializable
sealed interface TimelineItem {
    val id: ItemId
    val timelineStartUs: Long
    val durationUs: Long
    val locked: Boolean

    val timelineEndUs: Long get() = timelineStartUs + durationUs
}

/** A freeze-frame segment inside a video clip (source position where time stops). */
@Serializable
data class FreezeSegment(
    /** Position inside the source selection where the freeze starts. */
    val sourcePositionUs: Long,
    /** How long the frozen frame lasts on the timeline. */
    val durationUs: Long,
)

/**
 * A video/image/GIF clip on a VIDEO or OVERLAY track.
 *
 * Time semantics:
 * - [sourceInUs]/[sourceOutUs] select the used range of [assetId].
 * - [durationUs] is the **timeline** duration after speed is applied, i.e. for constant speed
 *   `(sourceOutUs - sourceInUs) / speed`. The timeline engine keeps it in sync via SpeedMath
 *   whenever trim/speed/curve operations change.
 * - Keyframe times are relative to [timelineStartUs].
 */
@Serializable
data class VideoClipItem(
    override val id: ItemId = ItemId(),
    val assetId: AssetId,
    override val timelineStartUs: Long = 0L,
    val sourceInUs: Long = 0L,
    val sourceOutUs: Long = 0L,
    override val durationUs: Long = 0L,
    /** z-order within the track (higher renders on top). */
    val layer: Int = 0,
    override val locked: Boolean = false,
    val speed: SpeedModel = SpeedModel.NORMAL,
    /** 0..1; 0 or [muted] silences the clip's own audio. */
    val volume: Float = 1f,
    val muted: Boolean = false,
    val fadeInUs: Long = 0L,
    val fadeOutUs: Long = 0L,
    val transform: SpatialTransform = SpatialTransform.DEFAULT,
    val contentFit: ContentFit = ContentFit.COVER,
    val crop: CropSettings = CropSettings.FULL,
    val reversed: Boolean = false,
    /** URI of the pre-rendered reversed clip (produced by MediaPreprocessor). */
    val reversedProxyUri: String? = null,
    val freezeSegments: List<FreezeSegment> = emptyList(),
    val effects: List<EffectInstance> = emptyList(),
    val effectKeyframes: List<EffectKeyframes> = emptyList(),
    val filter: FilterState = FilterState.NONE,
    val grading: Grading = Grading.DEFAULT,
    val mask: MaskSettings = MaskSettings.NONE,
    val chromaKey: ChromaKeySettings = ChromaKeySettings.DISABLED,
    val transitionIn: TransitionState? = null,
    val transitionOut: TransitionState? = null,
    val keyframes: KeyframeSet = KeyframeSet.EMPTY,
    /** Blend mode used when this clip renders above another (OVERLAY tracks). */
    val blendMode: BlendMode = BlendMode.NORMAL,
) : TimelineItem {

    val sourceDurationUs: Long get() = (sourceOutUs - sourceInUs).coerceAtLeast(0L)

    fun enabledEffects(): List<EffectInstance> = effects.filter { it.enabled }
}

/** Blend modes for overlay layers. */
@Serializable
enum class BlendMode {
    NORMAL,
    SCREEN,
    MULTIPLY,
    OVERLAY,
    ADD,
    DARKEN,
    LIGHTEN,
}

/** Font styling for a text layer. Colors are packed ARGB ints. */
@Serializable
data class TextStyle(
    /** Android font resource name (resolved by the design system's font registry). */
    val fontFamilyKey: String = "default",
    /** Text size in canvas-relative units: fraction of canvas height (e.g. 0.06). */
    val sizeFraction: Float = 0.06f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val alignment: TextAlignment = TextAlignment.CENTER,
    /** In em units. */
    val letterSpacing: Float = 0f,
    /** Multiplier of line height. */
    val lineSpacing: Float = 1.2f,
    val colorArgb: Int = 0xFFFFFFFF.toInt(),
    /** Optional two-stop gradient; when non-null overrides [colorArgb]. */
    val gradientStartArgb: Int? = null,
    val gradientEndArgb: Int? = null,
    val strokeColorArgb: Int = 0xFF000000.toInt(),
    /** Stroke width as fraction of text size; 0 disables stroke. */
    val strokeWidthFraction: Float = 0f,
    val shadowColorArgb: Int = 0x80000000.toInt(),
    val shadowRadiusFraction: Float = 0f,
    val shadowDxFraction: Float = 0f,
    val shadowDyFraction: Float = 0f,
    /** Semi-transparent box behind the text; 0 alpha disables. */
    val backgroundColorArgb: Int = 0x00000000,
    val backgroundCornerRadiusFraction: Float = 0.02f,
    val backgroundPaddingFraction: Float = 0.01f,
)

@Serializable
enum class TextAlignment { START, CENTER, END }

/** Text entrance animation. */
@Serializable
enum class TextAnimationIn {
    NONE, FADE, ZOOM, SLIDE, BOUNCE, TYPEWRITER, POP,
    GLITCH, WAVE, FLIP, SLIDE_UP, SLIDE_DOWN, ROTATE, NEON,
}

/** Text exit animation. */
@Serializable
enum class TextAnimationOut { NONE, FADE, SLIDE, ZOOM, BLUR, POP, GLITCH, SPIN }

/** Text loop animation (plays between in and out). */
@Serializable
enum class TextAnimationLoop { NONE, PULSE, BOUNCE, SHAKE, FLOATING, WAVE, GLITCH, NEON, WIGGLE }

@Serializable
data class TextAnimations(
    val inAnimation: TextAnimationIn = TextAnimationIn.NONE,
    val inDurationUs: Long = 400_000L,
    val outAnimation: TextAnimationOut = TextAnimationOut.NONE,
    val outDurationUs: Long = 400_000L,
    val loopAnimation: TextAnimationLoop = TextAnimationLoop.NONE,
)

/** A text layer on a TEXT track. */
@Serializable
data class TextItem(
    override val id: ItemId = ItemId(),
    val text: String = "",
    override val timelineStartUs: Long = 0L,
    override val durationUs: Long = 3_000_000L,
    val layer: Int = 0,
    override val locked: Boolean = false,
    val style: TextStyle = TextStyle(),
    val transform: SpatialTransform = SpatialTransform.DEFAULT,
    val animations: TextAnimations = TextAnimations(),
    val keyframes: KeyframeSet = KeyframeSet.EMPTY,
) : TimelineItem

/** Where a sticker's art comes from. */
@Serializable
sealed interface StickerSource {
    /** A single emoji (or emoji sequence) rendered with the system font. */
    @Serializable
    data class Emoji(val emoji: String) : StickerSource

    /** One of the built-in vector stickers (see the sticker registry in core:designsystem). */
    @Serializable
    data class BuiltIn(val stickerKey: String) : StickerSource

    /** User-imported PNG/WebP/GIF sticker (content:// URI). */
    @Serializable
    data class Imported(val uri: String) : StickerSource
}

@Serializable
data class StickerItem(
    override val id: ItemId = ItemId(),
    val source: StickerSource,
    override val timelineStartUs: Long = 0L,
    override val durationUs: Long = 3_000_000L,
    val layer: Int = 0,
    override val locked: Boolean = false,
    /** Size as fraction of canvas height. */
    val sizeFraction: Float = 0.18f,
    val transform: SpatialTransform = SpatialTransform.DEFAULT,
    val animations: TextAnimations = TextAnimations(),
    val keyframes: KeyframeSet = KeyframeSet.EMPTY,
    /** When non-null the sticker follows a motion-tracking path. */
    val trackingBindingId: String? = null,
) : TimelineItem

/** DSP effects available for audio items. All implemented as real AudioProcessors. */
@Serializable
data class AudioEffects(
    /** -1..1 shelf gain. */
    val bass: Float = 0f,
    /** -1..1 shelf gain. */
    val treble: Float = 0f,
    /** 0..1 wet mix. */
    val echo: Float = 0f,
    /** Echo delay in ms when [echo] > 0. */
    val echoDelayMs: Int = 300,
    /** 0..1 wet mix. */
    val reverb: Float = 0f,
    /** Simple spectral-gate noise reduction, 0..1 strength. */
    val noiseReduction: Float = 0f,
    /** Bandpass + soft compression tuned for speech. */
    val voiceEnhance: Boolean = false,
    /** Peak-normalize to -1 dBFS on decode (computed once, stored as gain). */
    val normalize: Boolean = false,
    val normalizeGain: Float = 1f,
) {
    val isNeutral: Boolean
        get() = bass == 0f && treble == 0f && echo == 0f && reverb == 0f &&
            noiseReduction == 0f && !voiceEnhance && !normalize

    companion object {
        val DEFAULT = AudioEffects()
    }
}

/** An audio clip (music, sound effect, voiceover, or audio detached from a video clip). */
@Serializable
data class AudioClipItem(
    override val id: ItemId = ItemId(),
    val assetId: AssetId,
    override val timelineStartUs: Long = 0L,
    val sourceInUs: Long = 0L,
    val sourceOutUs: Long = 0L,
    override val durationUs: Long = 0L,
    val layer: Int = 0,
    override val locked: Boolean = false,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val fadeInUs: Long = 0L,
    val fadeOutUs: Long = 0L,
    val speed: SpeedModel = SpeedModel.NORMAL,
    val audioEffects: AudioEffects = AudioEffects.DEFAULT,
    /** Set when this item was detached from a video clip (provenance for the UI). */
    val detachedFromItemId: ItemId? = null,
    val keyframes: KeyframeSet = KeyframeSet.EMPTY,
) : TimelineItem {
    val sourceDurationUs: Long get() = (sourceOutUs - sourceInUs).coerceAtLeast(0L)
}
