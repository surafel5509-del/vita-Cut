package com.vitacut.core.model

import kotlinx.serialization.Serializable

/** Track types. Each maps to a lane group in the timeline UI. */
@Serializable
enum class TrackKind {
    /** Primary video lane — drives the main preview player. */
    VIDEO,

    /** Picture-in-picture / image / GIF / sticker / text overlay lanes. */
    OVERLAY,
    TEXT,
    STICKER,

    /** Music & sound effects. */
    AUDIO,

    /** Recorded voiceovers (same engine as AUDIO, kept separate for UX). */
    VOICEOVER,
}

/**
 * A timeline track: an ordered lane of [TimelineItem]s.
 *
 * Invariants (maintained by the timeline engine, assumed by renderers):
 * - Items within a track never overlap in time (except items differing in [VideoClipItem.layer]
 *   on OVERLAY tracks, which composite by layer order).
 * - Items are sorted by timelineStartUs.
 */
@Serializable
data class Track(
    val id: TrackId = TrackId(),
    val kind: TrackKind,
    /** Display order; 0 is the lane nearest the playhead ruler (main video). */
    val order: Int = 0,
    val items: List<TimelineItem> = emptyList(),
    val locked: Boolean = false,
    val hidden: Boolean = false,
    val muted: Boolean = false,
    val name: String? = null,
) {
    val isAudioKind: Boolean get() = kind == TrackKind.AUDIO || kind == TrackKind.VOICEOVER

    fun itemWith(id: ItemId): TimelineItem? = items.firstOrNull { it.id == id }

    /** Items active at [timeUs], back-to-front for rendering. */
    fun itemsAt(timeUs: Long): List<TimelineItem> =
        items.filter { timeUs in it.timelineStartUs until it.timelineEndUs }
            .sortedBy { (it as? VideoClipItem)?.layer ?: (it as? TextItem)?.layer ?: 0 }
}
