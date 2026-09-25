package com.vitacut.core.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.SpeedChangingAudioProcessor
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.vitacut.core.common.logging.VitaLog
import com.vitacut.core.model.AudioClipItem
import com.vitacut.core.model.Project
import com.vitacut.core.model.TrackKind
import com.vitacut.core.model.VideoClipItem
import com.vitacut.core.rendering.audio.VitaSpeedProvider
import com.vitacut.core.rendering.overlay.OverlayComposer
import com.vitacut.core.rendering.pipeline.EffectsPipelineBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Translates an immutable [Project] snapshot into a Media3 Transformer [Composition].
 *
 * Graph shape:
 * - **One video sequence** — main VIDEO-track clips in timeline order, with generated black
 *   filler items covering leading gaps and inter-clip gaps so the output is continuous. Every
 *   item carries the per-clip effect chain from [EffectsPipelineBuilder] (identical to preview),
 *   including fused canvas placement, transitions and all overlay layers.
 * - **One audio sequence per audio track** — music/voiceover items with DSP processors, fades,
 *   speed ramps and gaps, mixed by the Transformer's audio mixer.
 * - Force-audio is enabled so silent stretches and speed ramps behave predictably.
 */
@Singleton
class CompositionFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pipeline: EffectsPipelineBuilder,
) {

    /** Builds the composition. [useProxies] swaps in proxy URIs where available. */
    fun create(
        project: Project,
        useProxies: Boolean,
        imageFrameSource: OverlayComposer.ImageFrameSource?,
        lutResolver: EffectsPipelineBuilder.LutResolver?,
    ): Composition {
        val videoSequence = buildVideoSequence(project, useProxies, imageFrameSource, lutResolver)
        val audioSequences = buildAudioSequences(project, useProxies)

        val sequences = listOf(videoSequence) + audioSequences
        return Composition.Builder(sequences)
            .experimentalSetForceAudioTrack(true)
            .build()
    }

    private fun buildVideoSequence(
        project: Project,
        useProxies: Boolean,
        imageFrameSource: OverlayComposer.ImageFrameSource?,
        lutResolver: EffectsPipelineBuilder.LutResolver?,
    ): EditedMediaItemSequence {
        val track = project.tracks
            .filter { it.kind == TrackKind.VIDEO && !it.hidden }
            .minByOrNull { it.order }

        val clips = (track?.items ?: emptyList())
            .filterIsInstance<VideoClipItem>()
            .sortedBy { it.timelineStartUs }

        val builder = EditedMediaItemSequence.Builder()
        var cursor = 0L
        var hasItems = false

        for (clip in clips) {
            val asset = project.asset(clip.assetId)
            if (asset == null) {
                VitaLog.w("CompositionFactory", "Missing asset for clip ${clip.id.value}; skipped")
                continue
            }
            if (!asset.codecSupported || !asset.available) continue

            // Gap before this clip → black filler carrying overlays for that window.
            if (clip.timelineStartUs - cursor > GAP_TOLERANCE_US) {
                builder.addItem(
                    blackFillerItem(project, cursor, clip.timelineStartUs, imageFrameSource),
                )
                hasItems = true
            }

            builder.addItem(videoItem(project, clip, asset.uri.let { uri ->
                if (useProxies) asset.playbackUri(true) else uri
            }, imageFrameSource, lutResolver))
            hasItems = true
            cursor = clip.timelineEndUs
        }

        if (!hasItems) {
            // Empty timeline export (e.g. text-only project): one full-duration filler.
            val duration = project.durationUs.coerceAtLeast(1_000_000L)
            builder.addItem(blackFillerItem(project, 0L, duration, imageFrameSource))
        }
        return builder.build()
    }

    private fun videoItem(
        project: Project,
        clip: VideoClipItem,
        uri: String,
        imageFrameSource: OverlayComposer.ImageFrameSource?,
        lutResolver: EffectsPipelineBuilder.LutResolver?,
    ): EditedMediaItem {
        val asset = project.asset(clip.assetId)!!
        val isImage = asset.durationUs <= 0L

        val mediaItemBuilder = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(clip.id.value)
        if (isImage) {
            mediaItemBuilder.setImageDurationMs(clip.durationUs / 1000L)
        } else {
            // Reversed clips render from their pre-computed proxy (contains the reversed frames).
            val effectiveUri = if (clip.reversed && clip.reversedProxyUri != null) {
                clip.reversedProxyUri!!
            } else uri
            mediaItemBuilder.setUri(effectiveUri)
            mediaItemBuilder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionUs(if (clip.reversed && clip.reversedProxyUri != null) 0L else clip.sourceInUs)
                    .setEndPositionUs(
                        if (clip.reversed && clip.reversedProxyUri != null) {
                            clip.sourceOutUs - clip.sourceInUs
                        } else clip.sourceOutUs,
                    )
                    .build(),
            )
        }

        val videoEffects = pipeline.videoEffectsFor(project, clip, imageFrameSource, lutResolver)
            .toMutableList()
        val audioProcessors = pipeline.audioEffectsFor(clip).toMutableList()

        // Speed: interlinked audio processor + video timestamp adjustment keep A/V in sync
        // through constant speeds and full speed-ramp curves.
        if (!isImage && pipeline.needsSpeedEffect(clip.speed)) {
            val provider = VitaSpeedProvider(clip.speed, clip.sourceInUs, clip.sourceOutUs)
            val speedPair = Effects.createExperimentalSpeedChangingEffect(provider)
            audioProcessors += speedPair.first as SpeedChangingAudioProcessor
            videoEffects += speedPair.second
        }

        val editedBuilder = EditedMediaItem.Builder(mediaItemBuilder.build())
            .setEffects(Effects(audioProcessors, videoEffects))
        if (isImage) {
            editedBuilder.setFrameRate(project.frameRate.toInt().coerceAtLeast(1))
            editedBuilder.setDurationUs(clip.durationUs.coerceAtLeast(1L))
        }
        if (clip.muted || clip.volume <= 0f || isImage) {
            // GainFade already zeroes muted clips; removeAudio lets the mixer skip decoding.
            if (clip.muted || isImage) editedBuilder.setRemoveAudio(true)
        }
        return editedBuilder.build()
    }

    private fun blackFillerItem(
        project: Project,
        windowStartUs: Long,
        windowEndUs: Long,
        imageFrameSource: OverlayComposer.ImageFrameSource?,
    ): EditedMediaItem {
        val durationUs = (windowEndUs - windowStartUs).coerceAtLeast(GAP_TOLERANCE_US)
        val blackUri = BlackFrameAsset.ensure(context)
        val mediaItem = MediaItem.Builder()
            .setUri(blackUri)
            .setMediaId("vitacut-filler-$windowStartUs")
            .setImageDurationMs(durationUs / 1000L)
            .build()
        val effects = pipeline.videoEffectsForBlankWindow(
            project, windowStartUs, windowEndUs, imageFrameSource,
        )
        return EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(emptyList(), effects))
            .setFrameRate(project.frameRate.toInt().coerceAtLeast(1))
            .setDurationUs(durationUs)
            .setRemoveAudio(true)
            .build()
    }

    private fun buildAudioSequences(project: Project, useProxies: Boolean): List<EditedMediaItemSequence> {
        val sequences = mutableListOf<EditedMediaItemSequence>()
        for (track in project.tracks.filter { it.isAudioKind && !it.hidden && !it.muted }) {
            val items = track.items.filterIsInstance<AudioClipItem>().sortedBy { it.timelineStartUs }
            if (items.isEmpty()) continue

            val builder = EditedMediaItemSequence.Builder()
            var cursor = 0L
            for (item in items) {
                val asset = project.asset(item.assetId) ?: continue
                if (!asset.codecSupported || !asset.available) continue

                if (item.timelineStartUs - cursor > GAP_TOLERANCE_US) {
                    builder.addGap(item.timelineStartUs - cursor)
                }
                val mediaItem = MediaItem.Builder()
                    .setUri(asset.playbackUri(useProxies))
                    .setMediaId(item.id.value)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionUs(item.sourceInUs)
                            .setEndPositionUs(item.sourceOutUs)
                            .build(),
                    )
                    .build()

                val audioProcessors = pipeline.audioEffectsFor(item).toMutableList()
                val videoEffects = mutableListOf<androidx.media3.common.Effect>()
                if (pipeline.needsSpeedEffect(item.speed)) {
                    val provider = VitaSpeedProvider(item.speed, item.sourceInUs, item.sourceOutUs)
                    val speedPair = Effects.createExperimentalSpeedChangingEffect(provider)
                    audioProcessors += speedPair.first as SpeedChangingAudioProcessor
                }

                builder.addItem(
                    EditedMediaItem.Builder(mediaItem)
                        .setEffects(Effects(audioProcessors, videoEffects))
                        .setRemoveVideo(true)
                        .build(),
                )
                cursor = item.timelineEndUs
            }
            sequences += builder.build()
        }
        return sequences
    }

    companion object {
        /** Gaps shorter than this are absorbed into neighbor clips (frame-rounding noise). */
        private const val GAP_TOLERANCE_US = 20_000L
    }
}

/**
 * Generates (once) a tiny solid-black PNG in the cache dir. Video-sequence gaps are filled with
 * this image item, whose effect chain paints the canvas background and any overlays — so gaps in
 * the main video still render text, stickers and canvas art exactly like the preview does.
 */
object BlackFrameAsset {

    @Volatile
    private var cachedPath: String? = null

    fun ensure(context: Context): String {
        cachedPath?.let { path -> if (File(path).exists()) return path }
        val dir = File(context.cacheDir, "export_assets").apply { mkdirs() }
        val file = File(dir, "black.png")
        if (!file.exists()) {
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.BLACK)
            file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            bitmap.recycle()
        }
        return file.absolutePath.also { cachedPath = it }
    }

    fun uri(context: Context): Uri = Uri.fromFile(File(ensure(context)))
}
