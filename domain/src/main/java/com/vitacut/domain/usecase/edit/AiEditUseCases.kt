package com.vitacut.domain.usecase.edit

import com.vitacut.core.model.AudioClipItem
import com.vitacut.core.model.ItemId
import com.vitacut.core.model.Keyframe
import com.vitacut.core.model.KeyframeProperty
import com.vitacut.core.model.KeyframeSet
import com.vitacut.core.model.KeyframeTrack
import com.vitacut.core.model.StickerItem
import com.vitacut.core.model.TextItem
import com.vitacut.core.model.TimelineItem
import com.vitacut.core.model.TrackPath
import com.vitacut.core.model.VideoClipItem
import com.vitacut.core.timeline.ProjectDocument
import com.vitacut.core.timeline.TimelineEngine
import javax.inject.Inject

/**
 * Applies the analyzer-produced auto-cut plan to the open document as ONE undo step:
 * 1. splits every item at the (beat-snapped) cut points — coordinates are pre-removal,
 * 2. ripple-deletes the silence ranges (descending, so earlier coordinates stay valid).
 *
 * Pure orchestration over [TimelineEngine]; the analysis itself lives in :core:ai so this stays
 * JVM-testable.
 */
class ApplyAutoCutUseCase @Inject constructor() {

    operator fun invoke(
        document: ProjectDocument,
        cutPointsUs: List<Long>,
        removeRanges: List<LongRange>,
        label: String = "autocut.apply",
    ) {
        document.update(label) { project ->
            var result = project
            for (cut in cutPointsUs.sorted()) {
                result = splitAllTracksAt(result, cut)
            }
            for (range in removeRanges.sortedByDescending { it.first }) {
                result = removeRange(result, range)
            }
            result
        }
    }

    companion object {
        /** Splits every item strictly containing [timeUs] on every (unlocked) track. */
        fun splitAllTracksAt(project: com.vitacut.core.model.Project, timeUs: Long): com.vitacut.core.model.Project {
            var out = project
            for (track in project.tracks) {
                val item = track.items.firstOrNull { it.timelineStartUs < timeUs && timeUs < it.timelineEndUs }
                if (item != null) out = TimelineEngine.splitItem(out, item.id, timeUs)
            }
            return out
        }

        /**
         * Removes the timeline window [range] from every track: split at both boundaries, then
         * ripple-delete the fully-contained pieces so following media closes the gap.
         */
        fun removeRange(project: com.vitacut.core.model.Project, range: LongRange): com.vitacut.core.model.Project {
            if (range.isEmpty()) return project
            val endUs = range.last + 1
            var p = splitAllTracksAt(project, range.first)
            p = splitAllTracksAt(p, endUs)
            for (track in p.tracks) {
                val doomed = track.items
                    .filter { it.timelineStartUs >= range.first && it.timelineEndUs <= endUs }
                    .sortedBy { it.timelineStartUs }
                for (item in doomed) {
                    val fresh = p.trackOfItem(item.id)?.itemWith(item.id) ?: continue
                    if (fresh.timelineStartUs >= range.first && fresh.timelineEndUs <= endUs) {
                        p = TimelineEngine.deleteItem(p, item.id, ripple = true)
                    }
                }
            }
            return p
        }
    }
}

/** Removes detected silence ranges as one undoable step (voiceover cleanup, jump-cut prep). */
class ApplySilenceRemovalUseCase @Inject constructor() {

    operator fun invoke(
        document: ProjectDocument,
        ranges: List<LongRange>,
        label: String = "silence.remove",
    ) {
        document.update(label) { project ->
            var result = project
            for (range in ranges.sortedByDescending { it.first }) {
                result = ApplyAutoCutUseCase.removeRange(result, range)
            }
            result
        }
    }
}

/**
 * Binds an auto-reframe (or motion-tracking) [TrackPath] to an item as keyframes — one undo
 * step, animatable by the universal keyframe engine in both preview and export.
 *
 * Coordinate mapping: path points are normalized frame fractions (origin top-left); the
 * placement shader consumes NDC translation (origin center, y-up) and absolute scale, so
 * x → (0.5 − x)·2, y → (y − 0.5)·2, scale → [scale] on both axes.
 *
 * @param pathTimeBaseUs added to every point time (pass the item's timeline start when the path
 *   was produced in absolute timeline time, 0 when it is item-relative).
 */
class ApplyAutoReframeUseCase @Inject constructor() {

    operator fun invoke(
        document: ProjectDocument,
        itemId: ItemId,
        path: TrackPath,
        scale: Float,
        pathTimeBaseUs: Long = 0L,
        label: String = "reframe.apply",
    ) {
        if (path.points.isEmpty()) return
        document.update(label) { project ->
            TimelineEngine.mapItem(project, itemId) { item ->
                val set = keyframeSetFor(item).withTrack(
                    KeyframeTrack(
                        property = KeyframeProperty.POSITION_X,
                        keyframes = path.points.map {
                            Keyframe(timeUs = pathTimeBaseUs + it.timeUs, value = (0.5f - it.x) * 2f)
                        },
                    ),
                ).withTrack(
                    KeyframeTrack(
                        property = KeyframeProperty.POSITION_Y,
                        keyframes = path.points.map {
                            Keyframe(timeUs = pathTimeBaseUs + it.timeUs, value = (it.y - 0.5f) * 2f)
                        },
                    ),
                ).withTrack(
                    KeyframeTrack(
                        property = KeyframeProperty.SCALE_X,
                        keyframes = listOf(Keyframe(timeUs = 0L, value = scale)),
                    ),
                ).withTrack(
                    KeyframeTrack(
                        property = KeyframeProperty.SCALE_Y,
                        keyframes = listOf(Keyframe(timeUs = 0L, value = scale)),
                    ),
                )
                withKeyframes(item, set)
            }
        }
    }

    private fun keyframeSetFor(item: TimelineItem): KeyframeSet = when (item) {
        is VideoClipItem -> item.keyframes
        is AudioClipItem -> item.keyframes
        is TextItem -> item.keyframes
        is StickerItem -> item.keyframes
    }

    private fun withKeyframes(item: TimelineItem, set: KeyframeSet): TimelineItem = when (item) {
        is VideoClipItem -> item.copy(keyframes = set)
        is AudioClipItem -> item.copy(keyframes = set)
        is TextItem -> item.copy(keyframes = set)
        is StickerItem -> item.copy(keyframes = set)
    }
}
