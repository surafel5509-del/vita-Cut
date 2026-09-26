package com.vitacut.feature.editor.preview

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.vitacut.core.common.logging.VitaLog
import com.vitacut.core.export.BlackFrameAsset
import com.vitacut.core.export.ExportResourceProvider
import com.vitacut.core.media.thumbnails.ThumbnailProvider
import com.vitacut.core.model.AudioClipItem
import com.vitacut.core.model.CanvasSettings
import com.vitacut.core.model.ItemId
import com.vitacut.core.model.Project
import com.vitacut.core.model.SpeedModel
import com.vitacut.core.model.TimelineItem
import com.vitacut.core.model.VideoClipItem
import com.vitacut.core.rendering.pipeline.EffectsPipelineBuilder
import com.vitacut.core.timeline.SpeedMath
import com.vitacut.core.timeline.TimelineQueries
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Preview playback host built on the SAME [EffectsPipelineBuilder] contract as export — the
 * parity requirement from the spec.
 *
 * Model:
 * - One main [ExoPlayer] shows the topmost VIDEO clip under the playhead (or a black filler
 *   item inside gaps, so overlays/captions still render over the canvas background).
 * - Video effects (grading, filters, FX, transitions, placement, overlays, captions) are set
 *   via `setVideoEffects` — identical chain to export.
 * - Speed: preview uses `setPlaybackSpeed` refreshed from [SpeedMath] on a ~30 ms ticker, so
 *   curve presets ramp audibly/visibly; export uses the exact speed-changing effect pair.
 * - Extra audio tracks (music/voiceover) play through a small pool of synced players with
 *   per-item volume/fade/speed approximation.
 * - The ticker maps player position back to timeline time (inverse speed mapping, binary
 *   search for curves) and publishes it as [timelinePositionUs] — the single playhead source
 *   of truth while playing.
 */
class PreviewController @Inject constructor(
    @ApplicationContext val context: Context,
    private val pipeline: EffectsPipelineBuilder,
    val thumbnailProvider: ThumbnailProvider,
    val waveformExtractor: com.vitacut.core.media.waveform.WaveformExtractor,
    private val resources: ExportResourceProvider,
) {

    val player: ExoPlayer by lazy {
        ExoPlayer.Builder(context).build().also { it.playWhenReady = false }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _timelinePositionUs = MutableStateFlow(0L)
    val timelinePositionUs: StateFlow<Long> = _timelinePositionUs.asStateFlow()

    private val _playbackEnded = MutableStateFlow(false)
    val playbackEnded: StateFlow<Boolean> = _playbackEnded.asStateFlow()

    private sealed interface Window {
        val startUs: Long
        val endUs: Long

        data class Clip(
            val clip: VideoClipItem,
            override val startUs: Long,
            override val endUs: Long,
        ) : Window

        data class Blank(override val startUs: Long, override val endUs: Long) : Window
    }

    private var window: Window? = null
    private var boundClip: VideoClipItem? = null
    private var boundCanvas: CanvasSettings? = null
    private var boundProject: Project? = null

    private val audioSlots = mutableListOf<AudioSlot>()
    private var tickerJob: Job? = null
    private var playing = false

    private class AudioSlot(val player: ExoPlayer) {
        var boundItemId: ItemId? = null
        var boundItem: TimelineItem? = null
    }

    // region public API

    fun play(project: Project, playheadUs: Long) {
        _playbackEnded.value = false
        ensureWindow(project, playheadUs)
        playing = true
        player.playWhenReady = true
        audioSlots.forEach { it.player.playWhenReady = true }
        syncAudioPool(project, playheadUs)
        startTicker(project)
    }

    fun pause() {
        playing = false
        stopTicker()
        player.playWhenReady = false
        audioSlots.forEach { it.player.playWhenReady = false }
    }

    fun seek(project: Project, timeUs: Long, keepPlaying: Boolean) {
        _playbackEnded.value = false
        ensureWindow(project, timeUs, forceRebind = true)
        seekMainToTimeline(project, timeUs)
        syncAudioPool(project, timeUs)
        _timelinePositionUs.value = timeUs
        if (keepPlaying) {
            playing = true
            player.playWhenReady = true
            audioSlots.forEach { it.player.playWhenReady = true }
            startTicker(project)
        }
    }

    /** Call after look edits (filters/grading/effects/canvas) while paused to refresh effects. */
    fun invalidateEffects(project: Project, timeUs: Long) {
        boundClip = null
        boundCanvas = null
        seek(project, timeUs, keepPlaying = playing)
    }

    fun release() {
        stopTicker()
        runCatching { player.release() }
        audioSlots.forEach { runCatching { it.player.release() } }
        audioSlots.clear()
        window = null
        boundClip = null
        boundProject = null
    }

    // endregion

    // region window management

    private fun ensureWindow(project: Project, timeUs: Long, forceRebind: Boolean = false) {
        boundProject = project
        val position = TimelineQueries.playbackPositionAt(project, timeUs)
        val newWindow: Window = if (position != null) {
            Window.Clip(position.clip, position.clip.timelineStartUs, position.clip.timelineEndUs)
        } else {
            blankWindowAround(project, timeUs)
        }
        val clipChanged = (newWindow as? Window.Clip)?.clip
        val needsRebind = forceRebind ||
            !sameWindow(newWindow, window) ||
            (clipChanged != null && clipChanged != boundClip) ||
            project.canvas != boundCanvas
        if (!needsRebind) return

        window = newWindow
        when (newWindow) {
            is Window.Clip -> bindClip(project, newWindow.clip, timeUs)
            is Window.Blank -> bindBlank(project, newWindow, timeUs)
        }
        boundClip = clipChanged
        boundCanvas = project.canvas
    }

    private fun sameWindow(a: Window?, b: Window?): Boolean = when {
        a is Window.Clip && b is Window.Clip -> a.clip.id == b.clip.id
        a is Window.Blank && b is Window.Blank -> a.startUs == b.startUs && a.endUs == b.endUs
        else -> false
    }

    /** Gap boundaries: previous item end → next item start across all VIDEO items. */
    private fun blankWindowAround(project: Project, timeUs: Long): Window.Blank {
        val videoItems = project.tracks
            .filter { it.kind == com.vitacut.core.model.TrackKind.VIDEO && !it.hidden }
            .flatMap { it.items }
            .sortedBy { it.timelineStartUs }
        val previousEnd = videoItems
            .filter { it.timelineEndUs <= timeUs }
            .maxOfOrNull { it.timelineEndUs } ?: 0L
        val nextStart = videoItems
            .filter { it.timelineStartUs > timeUs }
            .minOfOrNull { it.timelineStartUs }
            ?: project.durationUs.coerceAtLeast(previousEnd + 1_000_000L)
        return Window.Blank(previousEnd, nextStart.coerceAtLeast(previousEnd + 100_000L))
    }

    private fun bindClip(project: Project, clip: VideoClipItem, timeUs: Long) {
        val asset = project.asset(clip.assetId)
        if (asset == null) {
            VitaLog.w("Preview", "Clip ${clip.id.value} references a missing asset")
            bindBlank(project, Window.Blank(clip.timelineStartUs, clip.timelineEndUs), timeUs)
            return
        }
        val reversedProxy = clip.reversedProxyUri.takeIf { clip.reversed }
        val uri = reversedProxy ?: asset.playbackUri(useProxy = false)
        val clippingStart = if (reversedProxy != null) 0L else clip.sourceInUs
        val clippingEnd = if (reversedProxy != null) {
            clip.sourceOutUs - clip.sourceInUs
        } else {
            clip.sourceOutUs
        }
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clippingStart / 1000)
                    .setEndPositionMs((clippingEnd / 1000).coerceAtLeast(clippingStart / 1000 + 1))
                    .build(),
            )
            .build()
        player.setVideoEffects(
            pipeline.videoEffectsFor(
                project,
                clip,
                resources.imageFrameSource(),
                resources.lutResolver(),
            ),
        )
        player.setMediaItem(mediaItem)
        player.prepare()
        seekMainToTimeline(project, timeUs)
    }

    private fun bindBlank(project: Project, blank: Window.Blank, timeUs: Long) {
        val durationMs = ((blank.endUs - blank.startUs) / 1000).coerceAtLeast(100)
        val mediaItem = MediaItem.Builder()
            .setUri(BlackFrameAsset.uri(context))
            .setImageDurationMs(durationMs)
            .build()
        player.setVideoEffects(
            pipeline.videoEffectsForBlankWindow(
                project,
                blank.startUs,
                blank.endUs,
                resources.imageFrameSource(),
            ),
        )
        player.setMediaItem(mediaItem)
        player.prepare()
        val offsetMs = ((timeUs - blank.startUs) / 1000).coerceIn(0, durationMs)
        player.seekTo(offsetMs)
    }

    // endregion

    // region position mapping

    private fun seekMainToTimeline(project: Project, timeUs: Long) {
        val current = window ?: return
        when (current) {
            is Window.Clip -> {
                val clip = current.clip
                val offset = (timeUs - clip.timelineStartUs).coerceIn(0L, clip.durationUs)
                val sourceTime = SpeedMath.sourceTimeAt(
                    timelineOffsetUs = offset,
                    timelineDurationUs = clip.durationUs,
                    sourceInUs = clip.sourceInUs,
                    sourceOutUs = clip.sourceOutUs,
                    speed = clip.speed,
                )
                val playerPos = if (clip.reversed && clip.reversedProxyUri != null) {
                    sourceTime - clip.sourceInUs
                } else {
                    sourceTime
                }
                player.seekTo((playerPos / 1000).coerceAtLeast(0))
            }

            is Window.Blank -> {
                val offsetMs = ((timeUs - current.startUs) / 1000)
                    .coerceIn(0, ((current.endUs - current.startUs) / 1000))
                player.seekTo(offsetMs)
            }
        }
    }

    /** Player position → absolute timeline time (inverse of the speed mapping). */
    private fun timelineTimeFromPlayer(project: Project): Long {
        val current = window ?: return _timelinePositionUs.value
        return when (current) {
            is Window.Blank -> current.startUs + player.currentPosition * 1000L

            is Window.Clip -> {
                val clip = current.clip
                val playerPosUs = player.currentPosition * 1000L
                // For reversed proxies the file spans exactly [sourceIn..sourceOut], so the
                // mapping to source time is identical in both cases.
                val sourceTime = clip.sourceInUs + playerPosUs
                val offset = inverseSpeedOffset(clip, sourceTime)
                clip.timelineStartUs + offset
            }
        }
    }

    /** Exact for constant speed; binary search over the monotonic curve mapping otherwise. */
    private fun inverseSpeedOffset(clip: VideoClipItem, sourceTimeUs: Long): Long {
        val relative = (sourceTimeUs - clip.sourceInUs)
            .coerceIn(0L, (clip.sourceOutUs - clip.sourceInUs).coerceAtLeast(0L))
        val speed = clip.speed
        if (speed is SpeedModel.Constant) {
            return (relative / speed.speed.coerceAtLeast(0.01f)).toLong()
                .coerceIn(0L, clip.durationUs)
        }
        var low = 0L
        var high = clip.durationUs
        repeat(24) {
            val mid = (low + high) / 2
            val mapped = SpeedMath.sourceTimeAt(
                timelineOffsetUs = mid,
                timelineDurationUs = clip.durationUs,
                sourceInUs = clip.sourceInUs,
                sourceOutUs = clip.sourceOutUs,
                speed = clip.speed,
            ) - clip.sourceInUs
            if (mapped < relative) low = mid else high = mid
        }
        return ((low + high) / 2).coerceIn(0L, clip.durationUs)
    }

    // endregion

    // region ticker

    private fun startTicker(project: Project) {
        stopTicker()
        tickerJob = scope.launch {
            var lastSpeedUpdate = 0L
            var lastDriftCheck = 0L
            while (isActive && playing) {
                delay(30)
                val timeUs = timelineTimeFromPlayer(project)
                _timelinePositionUs.value = timeUs.coerceIn(0L, project.durationUs.coerceAtLeast(0L))

                // Refresh curve speed + volume/fades at ~10 Hz (cheap, avoids audio artifacts).
                val now = System.currentTimeMillis()
                if (now - lastSpeedUpdate > 100) {
                    lastSpeedUpdate = now
                    applyInstantaneous(project, timeUs)
                }
                if (now - lastDriftCheck > 500) {
                    lastDriftCheck = now
                    syncAudioPool(project, timeUs, driftCheck = true)
                }

                val current = window
                if (current != null && timeUs >= current.endUs - 30_000L) {
                    if (timeUs >= project.durationUs - 30_000L && project.durationUs > 0) {
                        pause()
                        _playbackEnded.value = true
                        break
                    }
                    // Roll into the next window without stopping transport.
                    ensureWindow(project, timeUs)
                    seekMainToTimeline(project, timeUs)
                }
                syncAudioPool(project, timeUs)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun applyInstantaneous(project: Project, timeUs: Long) {
        val current = window
        if (current is Window.Clip) {
            val clip = current.clip
            val offset = timeUs - clip.timelineStartUs
            val speedNow = SpeedMath.speedAtTimelineOffset(
                timelineOffsetUs = offset.coerceIn(0L, clip.durationUs),
                timelineDurationUs = clip.durationUs,
                sourceInUs = clip.sourceInUs,
                sourceOutUs = clip.sourceOutUs,
                speed = clip.speed,
            )
            if (kotlin.math.abs(player.playbackParameters.speed - speedNow) > 0.05f) {
                player.setPlaybackSpeed(speedNow.coerceIn(0.1f, 8f))
            }
            player.volume = TimelineQueries.clipVolumeAt(clip, timeUs)
        } else {
            player.volume = 1f
        }
    }

    // endregion

    // region audio pool

    private fun syncAudioPool(project: Project, timeUs: Long, driftCheck: Boolean = false) {
        val audible = TimelineQueries.audioAt(project, timeUs)
            // The main player already renders the driving video clip's audio.
            .filterNot { (window as? Window.Clip)?.clip?.id == it.id }
            .take(3)

        while (audioSlots.size < audible.size) {
            audioSlots += AudioSlot(ExoPlayer.Builder(context).build())
        }

        for (i in audioSlots.indices) {
            val slot = audioSlots[i]
            val desired = audible.getOrNull(i)
            if (desired == null) {
                if (slot.boundItemId != null) {
                    slot.player.stop()
                    slot.boundItemId = null
                    slot.boundItem = null
                }
                continue
            }
            val trackMuted = project.trackOfItem(desired.id)?.muted == true
            // AudioClipItem and (secondary) VideoClipItem both carry audible source windows.
            val sourceInUs: Long
            val sourceOutUs: Long
            val speedModel: SpeedModel
            val assetId: com.vitacut.core.model.AssetId
            when (desired) {
                is AudioClipItem -> {
                    sourceInUs = desired.sourceInUs
                    sourceOutUs = desired.sourceOutUs
                    speedModel = desired.speed
                    assetId = desired.assetId
                }

                is VideoClipItem -> {
                    sourceInUs = desired.sourceInUs
                    sourceOutUs = desired.sourceOutUs
                    speedModel = desired.speed
                    assetId = desired.assetId
                }

                else -> continue
            }
            if (slot.boundItemId != desired.id) {
                val asset = project.asset(assetId)
                if (asset == null) {
                    slot.boundItemId = null
                    continue
                }
                val mediaItem = MediaItem.Builder()
                    .setUri(asset.playbackUri(useProxy = false))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(sourceInUs / 1000)
                            .setEndPositionMs((sourceOutUs / 1000).coerceAtLeast(sourceInUs / 1000 + 1))
                            .build(),
                    )
                    .build()
                slot.player.setMediaItem(mediaItem)
                slot.player.prepare()
                slot.boundItemId = desired.id
                slot.boundItem = desired
            }
            val offsetInItem = (timeUs - desired.timelineStartUs)
                .coerceIn(0L, desired.durationUs.coerceAtLeast(1L))
            val sourcePos = SpeedMath.sourceTimeAt(
                timelineOffsetUs = offsetInItem,
                timelineDurationUs = desired.durationUs,
                sourceInUs = sourceInUs,
                sourceOutUs = sourceOutUs,
                speed = speedModel,
            )
            val expectedMs = (sourcePos - sourceInUs) / 1000
            if (driftCheck || slot.boundItemId == desired.id) {
                val drift = kotlin.math.abs(slot.player.currentPosition - expectedMs)
                if (drift > 180) slot.player.seekTo(expectedMs)
            }
            val volume = if (trackMuted) {
                0f
            } else {
                TimelineQueries.clipVolumeAt(desired, timeUs)
            }
            slot.player.volume = volume.coerceIn(0f, 1f)
            if (speedModel is SpeedModel.Constant) {
                slot.player.setPlaybackSpeed(speedModel.speed.coerceIn(0.1f, 8f))
            }
            slot.player.playWhenReady = playing
        }
    }

    // endregion
}
