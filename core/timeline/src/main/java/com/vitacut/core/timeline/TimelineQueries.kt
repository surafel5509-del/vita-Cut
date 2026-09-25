package com.vitacut.core.timeline

import com.vitacut.core.model.MediaAsset
import com.vitacut.core.model.Project
import com.vitacut.core.model.SpeedModel
import com.vitacut.core.model.TimelineItem
import com.vitacut.core.model.Track
import com.vitacut.core.model.TrackKind
import com.vitacut.core.model.VideoClipItem

/** Resolves what should be visible/audible at a given timeline position. */
object TimelineQueries {

    /** A clip active at [timeUs] plus its resolved source playback position. */
    data class PlaybackPosition(
        val clip: VideoClipItem,
        val track: Track,
        val asset: MediaAsset,
        /** Timestamp inside the source file the player must show right now. */
        val sourceTimeUs: Long,
        /** Instantaneous speed (for curve ramps). */
        val speed: Float,
    )

    /**
     * Finds the video clip driving the main preview at [timeUs].
     *
     * Resolution order: topmost visible, unlocked VIDEO track wins; OVERLAY tracks never drive
     * the main player in preview (they are composited in export and shown as transformed layers
     * in the editor UI).
     */
    fun playbackPositionAt(project: Project, timeUs: Long): PlaybackPosition? {
        val videoTracks = project.tracks
            .filter { it.kind == TrackKind.VIDEO && !it.hidden }
            .sortedBy { it.order }
        for (track in videoTracks) {
            val clip = track.items
                .filterIsInstance<VideoClipItem>()
                .firstOrNull { timeUs in it.timelineStartUs until it.timelineEndUs }
                ?: continue
            val asset = project.asset(clip.assetId) ?: continue
            val offset = timeUs - clip.timelineStartUs
            val sourceTime = SpeedMath.sourceTimeAt(
                timelineOffsetUs = offset,
                timelineDurationUs = clip.durationUs,
                sourceInUs = clip.sourceInUs,
                sourceOutUs = clip.sourceOutUs,
                speed = clip.speed,
            )
            val speedNow = SpeedMath.speedAtTimelineOffset(
                timelineOffsetUs = offset,
                timelineDurationUs = clip.durationUs,
                sourceInUs = clip.sourceInUs,
                sourceOutUs = clip.sourceOutUs,
                speed = clip.speed,
            )
            return PlaybackPosition(clip, track, asset, sourceTime, speedNow)
        }
        return null
    }

    /** All audio-bearing items audible at [timeUs] (music, voiceover, plus video clips' audio). */
    fun audioAt(project: Project, timeUs: Long): List<TimelineItem> = buildList {
        for (track in project.tracks) {
            if (track.hidden || track.muted) continue
            when (track.kind) {
                TrackKind.AUDIO, TrackKind.VOICEOVER ->
                    addAll(track.itemsAt(timeUs))

                TrackKind.VIDEO -> {
                    // Video clip audio is carried by the main player; listed here for mixers that
                    // render audio independently (export audio graph, silence detection).
                    addAll(track.itemsAt(timeUs))
                }

                else -> Unit
            }
        }
    }

    /** Overlays (text + stickers) visible at [timeUs], back-to-front. */
    fun overlaysAt(project: Project, timeUs: Long): List<TimelineItem> =
        project.overlayItems().filter { timeUs in it.timelineStartUs until it.timelineEndUs }

    /** Captions visible at [timeUs]. */
    fun captionAt(project: Project, timeUs: Long) =
        project.captions.captions.firstOrNull { timeUs in it.startUs until it.endUs }

    /** The neighbor of [clip] on the same track (previous/next by start time). */
    fun neighborsOf(track: Track, clip: TimelineItem): Pair<TimelineItem?, TimelineItem?> {
        val sorted = track.items.sortedBy { it.timelineStartUs }
        val index = sorted.indexOfFirst { it.id == clip.id }
        if (index < 0) return null to null
        return sorted.getOrNull(index - 1) to sorted.getOrNull(index + 1)
    }

    /** First free timeline position on [track] at or after [afterUs] for an item of [durationUs]. */
    fun firstFreeSlot(track: Track, durationUs: Long, afterUs: Long = 0L): Long {
        val sorted = track.items.sortedBy { it.timelineStartUs }
        var cursor = afterUs
        for (item in sorted) {
            if (item.timelineStartUs >= cursor + durationUs) return cursor
            cursor = maxOf(cursor, item.timelineEndUs)
        }
        return cursor
    }

    /** Whether moving [item] to [newStartUs] on [track] would overlap another (unlocked) item. */
    fun wouldOverlap(track: Track, item: TimelineItem, newStartUs: Long): Boolean {
        val newEnd = newStartUs + item.durationUs
        return track.items.any { other ->
            other.id != item.id &&
                newStartUs < other.timelineEndUs &&
                other.timelineStartUs < newEnd
        }
    }

    /** Effective per-clip volume for mixing at [timeUs] including fades. 0..1. */
    fun clipVolumeAt(item: TimelineItem, timeUs: Long): Float {
        val offset = timeUs - item.timelineStartUs
        return when (item) {
            is VideoClipItem -> envelope(offset, item.durationUs, item.volume, item.muted, item.fadeInUs, item.fadeOutUs)
            is com.vitacut.core.model.AudioClipItem ->
                envelope(offset, item.durationUs, item.volume, item.muted, item.fadeInUs, item.fadeOutUs)
            else -> 1f
        }
    }

    private fun envelope(
        offsetUs: Long,
        durationUs: Long,
        volume: Float,
        muted: Boolean,
        fadeInUs: Long,
        fadeOutUs: Long,
    ): Float {
        if (muted) return 0f
        var gain = volume.coerceIn(0f, 1f)
        if (fadeInUs > 0 && offsetUs < fadeInUs) {
            gain *= offsetUs.toFloat() / fadeInUs
        }
        val fadeOutStart = durationUs - fadeOutUs
        if (fadeOutUs > 0 && offsetUs > fadeOutStart) {
            gain *= ((durationUs - offsetUs).toFloat() / fadeOutUs).coerceIn(0f, 1f)
        }
        return gain.coerceIn(0f, 1f)
    }

    /** Speed model helper exposed for UI (speed sheet label). */
    fun describeSpeed(speed: SpeedModel): String = when (speed) {
        is SpeedModel.Constant -> "${trimFloat(speed.speed)}x"
        is SpeedModel.Curve -> "curve"
    }

    private fun trimFloat(value: Float): String =
        if (value % 1f == 0f) value.toInt().toString() else value.toString()
}
