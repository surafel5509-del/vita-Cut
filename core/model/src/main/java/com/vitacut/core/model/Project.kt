package com.vitacut.core.model

import kotlinx.serialization.Serializable

/**
 * The complete, serializable editing state of one video project — the "VitaCut Project" document.
 *
 * Design rules:
 * - **Immutable.** Every edit produces a new [Project] via `copy`. This gives crash-safe autosave,
 *   snapshot undo/redo and fearless multithreaded reads.
 * - **Self-contained.** Everything needed to re-render the video (assets by URI, tracks, items,
 *   effects, grading, keyframes, transitions, captions, canvas) lives here.
 * - **Forward tolerant.** Unknown JSON fields are ignored on load; [FORMAT_VERSION] guards
 *   migrations.
 */
@Serializable
data class Project(
    val id: ProjectId = ProjectId(),
    val name: String = "",
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val formatVersion: Int = FORMAT_VERSION,
    val canvas: CanvasSettings = CanvasSettings.DEFAULT,
    val frameRate: Float = 30f,
    /** All media referenced by this project, keyed by [AssetId.value]. */
    val assets: Map<String, MediaAsset> = emptyMap(),
    val tracks: List<Track> = emptyList(),
    val captions: CaptionSet = CaptionSet.EMPTY,
    /** Saved grading presets created inside this project. */
    val gradingPresets: List<GradingPreset> = emptyList(),
    /** Motion-tracking results, keyed by binding id (see core:ai tracking). */
    val trackingPaths: Map<String, TrackPath> = emptyMap(),
) {

    fun asset(id: AssetId): MediaAsset? = assets[id.value]

    /** Total timeline duration across all tracks. */
    val durationUs: Long
        get() = tracks.flatMap { it.items }.maxOfOrNull { it.timelineEndUs } ?: 0L

    fun track(id: TrackId): Track? = tracks.firstOrNull { it.id == id }

    fun trackOfItem(itemId: ItemId): Track? = tracks.firstOrNull { track ->
        track.items.any { it.id == itemId }
    }

    fun item(itemId: ItemId): TimelineItem? = tracks.firstNotNullOfOrNull { track ->
        track.items.firstOrNull { it.id == itemId }
    }

    /** The main video lane (created for every project). */
    fun mainVideoTrack(): Track? =
        tracks.filter { it.kind == TrackKind.VIDEO }.minByOrNull { it.order }

    fun videoClips(): List<VideoClipItem> =
        tracks.filter { it.kind == TrackKind.VIDEO || it.kind == TrackKind.OVERLAY }
            .flatMap { track -> track.items.filterIsInstance<VideoClipItem>() }
            .sortedBy { it.timelineStartUs }

    fun audioClips(): List<AudioClipItem> =
        tracks.filter { it.isAudioKind }
            .flatMap { track -> track.items.filterIsInstance<AudioClipItem>() }
            .sortedBy { it.timelineStartUs }

    fun textItems(): List<TextItem> =
        tracks.filter { it.kind == TrackKind.TEXT }
            .flatMap { track -> track.items.filterIsInstance<TextItem>() }

    fun stickerItems(): List<StickerItem> =
        tracks.filter { it.kind == TrackKind.STICKER || it.kind == TrackKind.OVERLAY }
            .flatMap { track -> track.items.filterIsInstance<StickerItem>() }

    /** All visual overlay items (text + stickers) in render order. */
    fun overlayItems(): List<TimelineItem> =
        (textItems() + stickerItems()).sortedBy {
            val layer = when (it) {
                is TextItem -> it.layer
                is StickerItem -> it.layer
                else -> 0
            }
            it.timelineStartUs * 1000 + layer
        }

    /** Resolution label for project cards, e.g. `1080x1920`. */
    val resolutionLabel: String get() = "${canvas.width}x${canvas.height}"

    val isEmptyTimeline: Boolean get() = tracks.all { it.items.isEmpty() }

    companion object {
        /** Bump when the schema changes in a way that needs migration. */
        const val FORMAT_VERSION = 1

        /** A brand-new empty project with one video lane and one audio lane. */
        fun create(
            name: String,
            canvas: CanvasSettings = CanvasSettings.DEFAULT,
            frameRate: Float = 30f,
            nowMs: Long,
        ): Project = Project(
            name = name,
            createdAtMs = nowMs,
            updatedAtMs = nowMs,
            canvas = canvas,
            frameRate = frameRate,
            tracks = listOf(
                Track(kind = TrackKind.VIDEO, order = 0),
                Track(kind = TrackKind.AUDIO, order = 0),
            ),
        )
    }
}

/**
 * A sampled motion-tracking result: the tracked point over time.
 *
 * [points] are (timeUs, x, y, scale) tuples in normalized canvas coordinates (center origin).
 * Produced by core:ai tracking and consumed by preview (per-frame overlay placement) and export
 * (OverlaySettings at presentationTimeUs).
 */
@Serializable
data class TrackPath(
    val points: List<TrackPoint> = emptyList(),
    /** Size of the tracked region at bind time, as fraction of canvas height. */
    val regionSizeFraction: Float = 0.2f,
) {
    fun sampleAt(timeUs: Long): TrackPoint? {
        if (points.isEmpty()) return null
        if (timeUs <= points.first().timeUs) return points.first()
        if (timeUs >= points.last().timeUs) return points.last()
        val index = points.indexOfLast { it.timeUs <= timeUs }
        val a = points[index]
        val b = points[(index + 1).coerceAtMost(points.lastIndex)]
        val span = (b.timeUs - a.timeUs).coerceAtLeast(1L)
        val t = (timeUs - a.timeUs).toFloat() / span
        return TrackPoint(
            timeUs = timeUs,
            x = KeyframeMath.lerp(a.x, b.x, t),
            y = KeyframeMath.lerp(a.y, b.y, t),
            scale = KeyframeMath.lerp(a.scale, b.scale, t),
        )
    }
}

@Serializable
data class TrackPoint(
    val timeUs: Long,
    val x: Float,
    val y: Float,
    val scale: Float = 1f,
)
