package com.vitacut.core.timeline

import com.vitacut.core.model.AssetId
import com.vitacut.core.model.AudioClipItem
import com.vitacut.core.model.CanvasBackground
import com.vitacut.core.model.CanvasSettings
import com.vitacut.core.model.CropSettings
import com.vitacut.core.model.EffectInstance
import com.vitacut.core.model.FilterState
import com.vitacut.core.model.Grading
import com.vitacut.core.model.ItemId
import com.vitacut.core.model.MediaAsset
import com.vitacut.core.model.Project
import com.vitacut.core.model.SpeedModel
import com.vitacut.core.model.SpatialTransform
import com.vitacut.core.model.StickerItem
import com.vitacut.core.model.TextItem
import com.vitacut.core.model.TimelineItem
import com.vitacut.core.model.Track
import com.vitacut.core.model.TrackId
import com.vitacut.core.model.TrackKind
import com.vitacut.core.model.VideoClipItem

/**
 * The timeline editing engine: every structural edit in Vita Cut is a pure function here.
 *
 * Conventions:
 * - Input and output are immutable [Project] values — the engine never mutates.
 * - Operations that cannot apply (wrong item type, locked track…) return the input unchanged;
 *   the ViewModel surfaces a message. Nothing throws for user-level mistakes.
 * - Durations of video clips are kept consistent with their speed model via [SpeedMath].
 */
object TimelineEngine {

    // region Assets & insertion

    /** Registers [assets] with the project (replacing any existing entry with the same id). */
    fun addAssets(project: Project, assets: List<MediaAsset>): Project =
        project.copy(assets = project.assets + assets.associateBy { it.id.value })

    fun removeAsset(project: Project, assetId: AssetId): Project = project.copy(
        assets = project.assets - assetId.value,
        tracks = project.tracks.map { track ->
            track.copy(
                items = track.items.filterNot { item ->
                    when (item) {
                        is VideoClipItem -> item.assetId == assetId
                        is AudioClipItem -> item.assetId == assetId
                        else -> false
                    }
                },
            )
        },
    )

    /**
     * Appends a new clip for [asset] to the end of the main video track (or creates one).
     * Images get [imageDurationUs] on the timeline.
     */
    fun appendClip(
        project: Project,
        asset: MediaAsset,
        imageDurationUs: Long = 3_000_000L,
        sourceInUs: Long = 0L,
        sourceOutUs: Long = asset.durationUs,
    ): Project {
        if (!asset.isVisual) return project
        val track = project.mainVideoTrack() ?: Track(kind = TrackKind.VIDEO, order = 0)
        val isStill = asset.kind == com.vitacut.core.model.MediaKind.IMAGE
        val effectiveOut = if (isStill || sourceOutUs <= sourceInUs) {
            sourceInUs // stills have no source range; duration comes from imageDurationUs
        } else {
            sourceOutUs.coerceAtMost(asset.durationUs)
        }
        val duration = if (isStill) {
            imageDurationUs
        } else {
            SpeedMath.timelineDurationUs(sourceInUs, effectiveOut, SpeedModel.NORMAL)
        }
        val start = track.items.maxOfOrNull { it.timelineEndUs } ?: 0L
        val clip = VideoClipItem(
            assetId = asset.id,
            timelineStartUs = start,
            sourceInUs = sourceInUs,
            sourceOutUs = effectiveOut,
            durationUs = duration,
        )
        return replaceTrack(ensureTrack(project, track), track.copy(items = track.items + clip))
    }

    /** Inserts [item] into [trackId] (creating the track when missing), shifting nothing else. */
    fun insertItem(project: Project, trackId: TrackId?, kind: TrackKind, item: TimelineItem): Project {
        val track = project.track(trackId ?: TrackId()) ?: project.tracks.firstOrNull { it.kind == kind }
            ?: Track(id = TrackId(), kind = kind, order = nextOrder(project, kind))
        val target = track.copy(items = (track.items + item).sortedBy { it.timelineStartUs })
        return replaceTrack(ensureTrack(project, target), target)
    }

    // endregion

    // region Structural edits

    /**
     * Splits [itemId] at the absolute timeline position [atUs] into two items.
     * For video clips, the source selection is divided at the mapped source time (speed-aware).
     */
    fun splitItem(project: Project, itemId: ItemId, atUs: Long): Project {
        val track = project.trackOfItem(itemId) ?: return project
        if (track.locked) return project
        val item = track.itemWith(itemId) ?: return project
        if (atUs <= item.timelineStartUs || atUs >= item.timelineEndUs) return project

        val leftDuration = atUs - item.timelineStartUs
        val rightDuration = item.durationUs - leftDuration

        val (left, right) = when (item) {
            is VideoClipItem -> {
                val splitSource = SpeedMath.sourceTimeAt(
                    leftDuration, item.durationUs, item.sourceInUs, item.sourceOutUs, item.speed,
                )
                val leftClip = item.copy(sourceOutUs = splitSource, durationUs = leftDuration)
                val rightClip = item.copy(
                    id = ItemId(),
                    timelineStartUs = atUs,
                    sourceInUs = splitSource,
                    durationUs = rightDuration,
                    // Transitions & one-shot animations belong to the head clip only.
                    transitionIn = null,
                    freezeSegments = emptyList(),
                    keyframes = com.vitacut.core.model.KeyframeSet.EMPTY,
                )
                leftClip to rightClip
            }

            is AudioClipItem -> {
                val splitSource = SpeedMath.sourceTimeAt(
                    leftDuration, item.durationUs, item.sourceInUs, item.sourceOutUs, item.speed,
                )
                val leftClip = item.copy(sourceOutUs = splitSource, durationUs = leftDuration, fadeOutUs = 0L)
                val rightClip = item.copy(
                    id = ItemId(),
                    timelineStartUs = atUs,
                    sourceInUs = splitSource,
                    durationUs = rightDuration,
                    fadeInUs = 0L,
                )
                leftClip to rightClip
            }

            is TextItem -> item.copy(durationUs = leftDuration) to
                item.copy(id = ItemId(), timelineStartUs = atUs, durationUs = rightDuration)

            is StickerItem -> item.copy(durationUs = leftDuration) to
                item.copy(id = ItemId(), timelineStartUs = atUs, durationUs = rightDuration)
        }

        val items = track.items.flatMap { existing ->
            if (existing.id == itemId) listOf(left, right) else listOf(existing)
        }
        return replaceTrack(project, track.copy(items = items))
    }

    /**
     * Trims the head of [itemId] so it starts at [newStartUs] (timeline coordinates). The source
     * selection moves accordingly; speed curves keep their normalized shape.
     */
    fun trimStart(project: Project, itemId: ItemId, newStartUs: Long): Project =
        mapItem(project, itemId) { item ->
            val delta = (newStartUs - item.timelineStartUs)
                .coerceIn(-item.durationUs + minDurationUs(), item.durationUs - minDurationUs())
            if (delta == 0L) return@mapItem item
            when (item) {
                is VideoClipItem -> {
                    val newSourceIn = SpeedMath.sourceTimeAt(
                        delta, item.durationUs, item.sourceInUs, item.sourceOutUs, item.speed,
                    )
                    item.copy(
                        timelineStartUs = item.timelineStartUs + delta,
                        sourceInUs = newSourceIn,
                        durationUs = item.durationUs - delta,
                    )
                }

                is AudioClipItem -> {
                    val newSourceIn = SpeedMath.sourceTimeAt(
                        delta, item.durationUs, item.sourceInUs, item.sourceOutUs, item.speed,
                    )
                    item.copy(
                        timelineStartUs = item.timelineStartUs + delta,
                        sourceInUs = newSourceIn,
                        durationUs = item.durationUs - delta,
                    )
                }

                else -> item.copy(
                    timelineStartUs = item.timelineStartUs + delta,
                    durationUs = item.durationUs - delta,
                )
            }
        }

    /** Trims the tail of [itemId] so it ends at [newEndUs] (timeline coordinates). */
    fun trimEnd(project: Project, itemId: ItemId, newEndUs: Long): Project =
        mapItem(project, itemId) { item ->
            val newDuration = (newEndUs - item.timelineStartUs)
                .coerceIn(minDurationUs(), item.durationUs + sourceSlack(project, item))
            if (newDuration == item.durationUs) return@mapItem item
            when (item) {
                is VideoClipItem -> {
                    val newSourceOut = SpeedMath.sourceTimeAt(
                        newDuration, item.durationUs, item.sourceInUs, item.sourceOutUs, item.speed,
                    ).coerceAtMost(assetEnd(project, item))
                    item.copy(sourceOutUs = newSourceOut, durationUs = newDuration)
                }

                is AudioClipItem -> {
                    val newSourceOut = SpeedMath.sourceTimeAt(
                        newDuration, item.durationUs, item.sourceInUs, item.sourceOutUs, item.speed,
                    ).coerceAtMost(assetEnd(project, item))
                    item.copy(sourceOutUs = newSourceOut, durationUs = newDuration)
                }

                else -> item.copy(durationUs = newDuration)
            }
        }

    /** Extends the clip tail into unused source media beyond sourceOut (untrim), when available. */
    private fun sourceSlack(project: Project, item: TimelineItem): Long = when (item) {
        is VideoClipItem -> {
            val asset = project.asset(item.assetId)
            if (asset == null || asset.kind == com.vitacut.core.model.MediaKind.IMAGE) 0L
            else SpeedMath.timelineDurationUs(item.sourceInUs, asset.durationUs, item.speed) - item.durationUs
        }

        is AudioClipItem -> {
            val asset = project.asset(item.assetId)
            if (asset == null) 0L
            else SpeedMath.timelineDurationUs(item.sourceInUs, asset.durationUs, item.speed) - item.durationUs
        }

        else -> Long.MAX_VALUE / 2 // text/stickers can be extended freely
    }

    private fun assetEnd(project: Project, item: TimelineItem): Long = when (item) {
        is VideoClipItem -> project.asset(item.assetId)?.durationUs ?: item.sourceOutUs
        is AudioClipItem -> project.asset(item.assetId)?.durationUs ?: item.sourceOutUs
        else -> Long.MAX_VALUE
    }

    /**
     * Moves [itemId] to [newStartUs], optionally onto [newTrackId]. Overlapping neighbors on the
     * destination are pushed aside (ripple forward) so items never overlap on one track.
     */
    fun moveItem(
        project: Project,
        itemId: ItemId,
        newStartUs: Long,
        newTrackId: TrackId? = null,
    ): Project {
        val sourceTrack = project.trackOfItem(itemId) ?: return project
        if (sourceTrack.locked) return project
        val item = sourceTrack.itemWith(itemId) ?: return project
        val start = newStartUs.coerceAtLeast(0L)

        val destination = newTrackId
            ?.let { project.track(it) }
            ?.takeIf { !it.locked && it.acceptsKind(item) }
            ?: sourceTrack

        val moved = retime(item, start)

        // 1. Remove the item from its current track.
        val sourceWithout = sourceTrack.copy(items = sourceTrack.items.filterNot { it.id == itemId })
        var result = replaceTrack(project, sourceWithout)

        // 2. Insert into the destination track, pushing overlapping neighbors aside.
        val destBase = (if (destination.id == sourceTrack.id) sourceWithout else destination)
            .copy(items = destination.items.filterNot { it.id == itemId })
        val resolved = resolveOverlaps(destBase, moved)
        result = replaceTrack(ensureTrack(result, resolved), resolved)
        return result
    }

    private fun Track.acceptsKind(item: TimelineItem): Boolean = when (item) {
        is VideoClipItem -> kind == TrackKind.VIDEO || kind == TrackKind.OVERLAY
        is AudioClipItem -> kind == TrackKind.AUDIO || kind == TrackKind.VOICEOVER
        is TextItem -> kind == TrackKind.TEXT
        is StickerItem -> kind == TrackKind.STICKER || kind == TrackKind.OVERLAY
    }

    private fun retime(item: TimelineItem, newStartUs: Long): TimelineItem = when (item) {
        is VideoClipItem -> item.copy(timelineStartUs = newStartUs)
        is AudioClipItem -> item.copy(timelineStartUs = newStartUs)
        is TextItem -> item.copy(timelineStartUs = newStartUs)
        is StickerItem -> item.copy(timelineStartUs = newStartUs)
    }

    /**
     * Places [moved] on [track], shifting any neighbor it now overlaps forward so items never
     * overlap. Items entirely before the moved clip are left untouched (the caller clamps the
     * drop position against them via snapping/overlap checks in the UI layer).
     */
    private fun resolveOverlaps(track: Track, moved: TimelineItem): Track {
        val result = mutableListOf<TimelineItem>()
        var cursorEnd = moved.timelineEndUs
        for (other in track.items.sortedBy { it.timelineStartUs }) {
            if (other.id == moved.id) continue
            if (other.timelineEndUs <= moved.timelineStartUs) {
                // Fully before — keep as-is.
                result += other
            } else if (other.timelineStartUs < cursorEnd) {
                // Overlaps the moved item (or an already-shifted one) — push forward.
                result += retime(other, cursorEnd)
                cursorEnd += other.durationUs
            } else {
                result += other
                cursorEnd = maxOf(cursorEnd, other.timelineEndUs)
            }
        }
        return track.copy(items = (result + moved).sortedBy { it.timelineStartUs })
    }

    /** Deletes [itemId]. When [ripple], later items on the same track close the gap. */
    fun deleteItem(project: Project, itemId: ItemId, ripple: Boolean = false): Project {
        val track = project.trackOfItem(itemId) ?: return project
        if (track.locked) return project
        val removed = track.itemWith(itemId) ?: return project
        var items = track.items.filterNot { it.id == itemId }
        if (ripple) {
            val gap = removed.durationUs
            items = items.map { item ->
                if (item.timelineStartUs >= removed.timelineEndUs) {
                    retime(item, (item.timelineStartUs - gap).coerceAtLeast(0L))
                } else item
            }
        }
        val newTrack = track.copy(items = items)
        val cleaned = if (items.isEmpty() && track.kind != TrackKind.VIDEO) null else newTrack
        return if (cleaned == null) {
            project.copy(tracks = project.tracks.filterNot { it.id == track.id })
        } else {
            replaceTrack(project, cleaned)
        }
    }

    /** Duplicates [itemId] right after the original (source selection, effects and all). */
    fun duplicateItem(project: Project, itemId: ItemId): Project {
        val track = project.trackOfItem(itemId) ?: return project
        if (track.locked) return project
        val item = track.itemWith(itemId) ?: return project
        val copy = when (item) {
            is VideoClipItem -> item.copy(id = ItemId(), timelineStartUs = item.timelineEndUs)
            is AudioClipItem -> item.copy(id = ItemId(), timelineStartUs = item.timelineEndUs)
            is TextItem -> item.copy(id = ItemId(), timelineStartUs = item.timelineEndUs)
            is StickerItem -> item.copy(id = ItemId(), timelineStartUs = item.timelineEndUs)
        }
        return replaceTrack(project, track.copy(items = (track.items + copy).sortedBy { it.timelineStartUs }))
    }

    // endregion

    // region Speed / volume / audio

    /** Sets a new speed model and recomputes the timeline duration from the source selection. */
    fun setSpeed(project: Project, itemId: ItemId, speed: SpeedModel): Project =
        mapItem(project, itemId) { item ->
            when (item) {
                is VideoClipItem -> {
                    val duration = if (item.isStill(project)) item.durationUs
                    else SpeedMath.timelineDurationUs(item.sourceInUs, item.sourceOutUs, speed)
                    item.copy(speed = speed, durationUs = duration.coerceAtLeast(minDurationUs()))
                }

                is AudioClipItem -> {
                    val duration = SpeedMath.timelineDurationUs(item.sourceInUs, item.sourceOutUs, speed)
                    item.copy(speed = speed, durationUs = duration.coerceAtLeast(minDurationUs()))
                }

                else -> item
            }
        }

    fun setClipVolume(project: Project, itemId: ItemId, volume: Float): Project =
        mapItem(project, itemId) { item ->
            when (item) {
                is VideoClipItem -> item.copy(volume = volume.coerceIn(0f, 1f))
                is AudioClipItem -> item.copy(volume = volume.coerceIn(0f, 1f))
                else -> item
            }
        }

    fun setClipMuted(project: Project, itemId: ItemId, muted: Boolean): Project =
        mapItem(project, itemId) { item ->
            when (item) {
                is VideoClipItem -> item.copy(muted = muted)
                is AudioClipItem -> item.copy(muted = muted)
                else -> item
            }
        }

    fun setClipFades(project: Project, itemId: ItemId, fadeInUs: Long, fadeOutUs: Long): Project =
        mapItem(project, itemId) { item ->
            when (item) {
                is VideoClipItem -> item.copy(
                    fadeInUs = fadeInUs.coerceAtLeast(0L),
                    fadeOutUs = fadeOutUs.coerceAtLeast(0L),
                )

                is AudioClipItem -> item.copy(
                    fadeInUs = fadeInUs.coerceAtLeast(0L),
                    fadeOutUs = fadeOutUs.coerceAtLeast(0L),
                )

                else -> item
            }
        }

    /**
     * Detaches the audio of a video clip into a new [AudioClipItem] on an AUDIO track and mutes
     * the source clip. The detached item keeps the same timeline window.
     */
    fun detachAudio(project: Project, itemId: ItemId): Project {
        val track = project.trackOfItem(itemId) ?: return project
        val clip = track.itemWith(itemId) as? VideoClipItem ?: return project
        if (track.locked) return project
        val asset = project.asset(clip.assetId) ?: return project
        if (!asset.hasAudioTrack) return project

        val mutedClip = clip.copy(muted = true)
        val withMute = replaceItem(project, mutedClip)

        val audioTrack = withMute.tracks.firstOrNull { it.kind == TrackKind.AUDIO }
            ?: Track(kind = TrackKind.AUDIO, order = nextOrder(withMute, TrackKind.AUDIO))
        val audioItem = AudioClipItem(
            assetId = clip.assetId,
            timelineStartUs = clip.timelineStartUs,
            sourceInUs = clip.sourceInUs,
            sourceOutUs = clip.sourceOutUs,
            durationUs = clip.durationUs,
            detachedFromItemId = clip.id,
        )
        val destination = audioTrack.copy(items = (audioTrack.items + audioItem).sortedBy { it.timelineStartUs })
        return replaceTrack(ensureTrack(withMute, destination), destination)
    }

    /** Inserts a freeze frame at [atUs] lasting [durationUs]; later items ripple back. */
    fun addFreezeFrame(project: Project, itemId: ItemId, atUs: Long, durationUs: Long): Project {
        val track = project.trackOfItem(itemId) ?: return project
        if (track.locked) return project
        val clip = track.itemWith(itemId) as? VideoClipItem ?: return project
        if (atUs !in clip.timelineStartUs..clip.timelineEndUs || durationUs <= 0) return project
        val sourcePos = SpeedMath.sourceTimeAt(
            atUs - clip.timelineStartUs, clip.durationUs, clip.sourceInUs, clip.sourceOutUs, clip.speed,
        ) - clip.sourceInUs
        val segment = com.vitacut.core.model.FreezeSegment(sourcePos, durationUs)
        val updated = clip.copy(
            durationUs = clip.durationUs + durationUs,
            freezeSegments = clip.freezeSegments + segment,
        )
        val shifted = track.copy(
            items = track.items.map { item ->
                if (item.id == itemId) updated
                else if (item.timelineStartUs >= clip.timelineEndUs) {
                    retime(item, item.timelineStartUs + durationUs)
                } else item
            },
        )
        return replaceTrack(project, shifted)
    }

    /** Marks a clip reversed; [reversedProxyUri] is filled in by MediaPreprocessor once ready. */
    fun setReversed(project: Project, itemId: ItemId, reversed: Boolean, proxyUri: String? = null): Project =
        mapItem(project, itemId) { item ->
            if (item is VideoClipItem) item.copy(reversed = reversed, reversedProxyUri = proxyUri ?: item.reversedProxyUri)
            else item
        }

    // endregion

    // region Item property edits (effects, filters, grading, transform…)

    fun setTransform(project: Project, itemId: ItemId, transform: SpatialTransform): Project =
        mapItem(project, itemId) { item -> withTransform(item, transform) }

    private fun withTransform(item: TimelineItem, transform: SpatialTransform): TimelineItem =
        when (item) {
            is VideoClipItem -> item.copy(transform = transform)
            is TextItem -> item.copy(transform = transform)
            is StickerItem -> item.copy(transform = transform)
            is AudioClipItem -> item
        }

    fun setCrop(project: Project, itemId: ItemId, crop: CropSettings): Project =
        mapItem(project, itemId) { item ->
            if (item is VideoClipItem) item.copy(crop = crop) else item
        }

    fun setContentFit(project: Project, itemId: ItemId, fit: com.vitacut.core.model.ContentFit): Project =
        mapItem(project, itemId) { item ->
            if (item is VideoClipItem) item.copy(contentFit = fit) else item
        }

    fun addEffect(project: Project, itemId: ItemId, effect: EffectInstance): Project =
        mapItem(project, itemId) { item ->
            if (item is VideoClipItem) item.copy(effects = item.effects + effect) else item
        }

    fun removeEffect(project: Project, itemId: ItemId, effectId: String): Project =
        mapItem(project, itemId) { item ->
            if (item is VideoClipItem) {
                item.copy(
                    effects = item.effects.filterNot { it.id == effectId },
                    effectKeyframes = item.effectKeyframes.filterNot { it.effectId == effectId },
                )
            } else item
        }

    fun setEffectEnabled(project: Project, itemId: ItemId, effectId: String, enabled: Boolean): Project =
        mapItem(project, itemId) { item ->
            if (item is VideoClipItem) {
                item.copy(effects = item.effects.map { if (it.id == effectId) it.copy(enabled = enabled) else it })
            } else item
        }

    fun setEffectIntensity(project: Project, itemId: ItemId, effectId: String, intensity: Float): Project =
        mapItem(project, itemId) { item ->
            if (item is VideoClipItem) {
                item.copy(
                    effects = item.effects.map {
                        if (it.id == effectId) it.copy(intensity = intensity.coerceIn(0f, 1f)) else it
                    },
                )
            } else item
        }

    fun setFilter(project: Project, itemId: ItemId, filter: FilterState): Project =
        mapItem(project, itemId) { item ->
            if (item is VideoClipItem) item.copy(filter = filter) else item
        }

    /** Applies [filter] to every video clip on every track ("Apply to all"). */
    fun setFilterAllClips(project: Project, filter: FilterState): Project = project.copy(
        tracks = project.tracks.map { track ->
            track.copy(
                items = track.items.map { item ->
                    if (item is VideoClipItem) item.copy(filter = filter) else item
                },
            )
        },
    )

    fun setGrading(project: Project, itemId: ItemId, grading: Grading): Project =
        mapItem(project, itemId) { item ->
            if (item is VideoClipItem) item.copy(grading = grading) else item
        }

    fun setGradingAllClips(project: Project, grading: Grading): Project = project.copy(
        tracks = project.tracks.map { track ->
            track.copy(
                items = track.items.map { item ->
                    if (item is VideoClipItem) item.copy(grading = grading) else item
                },
            )
        },
    )

    fun setMask(project: Project, itemId: ItemId, mask: com.vitacut.core.model.MaskSettings): Project =
        mapItem(project, itemId) { item ->
            if (item is VideoClipItem) item.copy(mask = mask) else item
        }

    fun setChromaKey(project: Project, itemId: ItemId, chroma: com.vitacut.core.model.ChromaKeySettings): Project =
        mapItem(project, itemId) { item ->
            if (item is VideoClipItem) item.copy(chromaKey = chroma) else item
        }

    fun setBlendMode(project: Project, itemId: ItemId, mode: com.vitacut.core.model.BlendMode): Project =
        mapItem(project, itemId) { item ->
            if (item is VideoClipItem) item.copy(blendMode = mode) else item
        }

    fun setTransition(
        project: Project,
        itemId: ItemId,
        transition: com.vitacut.core.model.TransitionState?,
        atEnd: Boolean,
    ): Project = mapItem(project, itemId) { item ->
        if (item is VideoClipItem) {
            if (atEnd) item.copy(transitionOut = transition) else item.copy(transitionIn = transition)
        } else item
    }

    /** Copy effects+grading+filter from [fromId] to [toId] ("paste attributes"). */
    fun pasteAttributes(project: Project, fromId: ItemId, toId: ItemId): Project {
        val source = project.item(fromId) as? VideoClipItem ?: return project
        return mapItem(project, toId) { item ->
            if (item is VideoClipItem) {
                item.copy(
                    effects = source.effects.map { it.copy(id = com.vitacut.core.model.newId()) },
                    filter = source.filter,
                    grading = source.grading,
                    mask = source.mask,
                    chromaKey = source.chromaKey,
                )
            } else item
        }
    }

    // endregion

    // region Text & stickers

    fun updateText(project: Project, itemId: ItemId, transform: (TextItem) -> TextItem): Project =
        mapItem(project, itemId) { item -> if (item is TextItem) transform(item) else item }

    fun updateSticker(project: Project, itemId: ItemId, transform: (StickerItem) -> StickerItem): Project =
        mapItem(project, itemId) { item -> if (item is StickerItem) transform(item) else item }

    // endregion

    // region Tracks

    fun setTrackLocked(project: Project, trackId: TrackId, locked: Boolean): Project =
        mapTrack(project, trackId) { it.copy(locked = locked) }

    fun setTrackHidden(project: Project, trackId: TrackId, hidden: Boolean): Project =
        mapTrack(project, trackId) { it.copy(hidden = hidden) }

    fun setTrackMuted(project: Project, trackId: TrackId, muted: Boolean): Project =
        mapTrack(project, trackId) { it.copy(muted = muted) }

    /** Reorders [trackId] to [newIndex] among tracks of the same kind group in the editor list. */
    fun reorderTrack(project: Project, trackId: TrackId, newIndex: Int): Project {
        val track = project.track(trackId) ?: return project
        val without = project.tracks.filterNot { it.id == trackId }
        val index = newIndex.coerceIn(0, without.size)
        val reordered = without.toMutableList().apply { add(index, track) }
        return project.copy(tracks = reordered.mapIndexed { i, t -> t.copy(order = i) })
    }

    fun addTrack(project: Project, kind: TrackKind): Project {
        val track = Track(kind = kind, order = nextOrder(project, kind))
        return project.copy(tracks = project.tracks + track)
    }

    fun removeTrack(project: Project, trackId: TrackId): Project =
        project.copy(tracks = project.tracks.filterNot { it.id == trackId })

    // endregion

    // region Canvas

    fun setCanvas(project: Project, canvas: CanvasSettings): Project = project.copy(canvas = canvas)

    fun setCanvasBackground(project: Project, background: CanvasBackground): Project =
        project.copy(canvas = project.canvas.copy(background = background))

    fun setFrameRate(project: Project, frameRate: Float): Project =
        project.copy(frameRate = frameRate)

    // endregion

    // region Generic plumbing

    /** Applies [block] to the item with [itemId] wherever it lives. */
    fun mapItem(project: Project, itemId: ItemId, block: (TimelineItem) -> TimelineItem): Project =
        project.copy(
            tracks = project.tracks.map { track ->
                if (track.items.none { it.id == itemId }) track
                else track.copy(items = track.items.map { if (it.id == itemId) block(it) else it })
            },
        )

    fun mapTrack(project: Project, trackId: TrackId, block: (Track) -> Track): Project =
        project.copy(tracks = project.tracks.map { if (it.id == trackId) block(it) else it })

    fun replaceItem(project: Project, item: TimelineItem): Project {
        val track = project.trackOfItem(item.id) ?: return project
        return replaceTrack(project, track.copy(items = track.items.map { if (it.id == item.id) item else it }))
    }

    fun replaceTrack(project: Project, track: Track): Project =
        project.copy(
            tracks = project.tracks.map { if (it.id == track.id) track else it },
        )

    fun ensureTrack(project: Project, track: Track): Project =
        if (project.tracks.any { it.id == track.id }) project
        else project.copy(tracks = project.tracks + track)

    fun nextOrder(project: Project, kind: TrackKind): Int =
        (project.tracks.filter { it.kind == kind }.maxOfOrNull { it.order } ?: -1) + 1

    fun minDurationUs(): Long = 100_000L // one tenth of a second

    private fun VideoClipItem.isStill(project: Project): Boolean =
        project.asset(assetId)?.let { !it.hasAudioTrack && it.durationUs <= 0L } ?: false

    // endregion
}
