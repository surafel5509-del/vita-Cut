package com.vitacut.core.ai.beat

import com.vitacut.core.ai.silence.SilenceRemover
import kotlin.math.abs

/**
 * "Auto cut" planner: turns beat + silence analysis into a concrete edit plan the timeline
 * engine can apply transactionally (so it lands in undo history as one step).
 *
 * The plan has three independent parts, each toggleable from the UI:
 * - [CutPlan.removeRanges] — silences long enough to hurt pacing,
 * - [CutPlan.highlightRanges] — voiced/sounding segments worth keeping (Montage style),
 * - [CutPlan.cutPointsUs] — suggested split points snapped to the musical beat grid.
 *
 * Pure Kotlin over already-computed envelopes → fully unit-testable, no media access.
 */
object AutoCutAnalyzer {

    data class AutoCutConfig(
        /** Desired average clip length when snapping beat cuts. */
        val targetClipUs: Long = 2_000_000L,
        /** Silences longer than this are proposed for removal. */
        val maxSilenceUs: Long = 1_200_000L,
        /** Segments shorter than this are not standalone highlights. */
        val minHighlightUs: Long = 800_000L,
        val snapToBeats: Boolean = true,
        val removeSilences: Boolean = true,
    )

    data class CutPlan(
        val cutPointsUs: List<Long>,
        val highlightRanges: List<LongRange>,
        val removeRanges: List<SilenceRemover.SilenceRange>,
        val bpm: Float,
    ) {
        companion object {
            val EMPTY = CutPlan(emptyList(), emptyList(), emptyList(), 0f)
        }
    }

    /**
     * @param beats beat analysis of the *music/audio* envelope
     * @param silences silence analysis of the same envelope (usually the voice track)
     * @param durationUs total timeline duration
     */
    fun plan(
        beats: BeatDetector.BeatResult,
        silences: List<SilenceRemover.SilenceRange>,
        durationUs: Long,
        config: AutoCutConfig = AutoCutConfig(),
    ): CutPlan {
        if (durationUs <= 0L) return CutPlan.EMPTY

        val removeRanges = if (config.removeSilences) {
            silences.filter { it.durationUs >= config.maxSilenceUs }
        } else emptyList()

        // Highlight ranges = complement of ALL detected silences (kept segments), filtered.
        val highlights = voicedSegments(silences, durationUs)
            .filter { it.last - it.first >= config.minHighlightUs }

        val cutPoints = if (config.snapToBeats && beats.beatTimesUs.isNotEmpty()) {
            beatSnappedCuts(beats, durationUs, config.targetClipUs)
        } else {
            regularCuts(durationUs, config.targetClipUs)
        }

        return CutPlan(
            cutPointsUs = cutPoints.distinct().sorted(),
            highlightRanges = highlights,
            removeRanges = removeRanges,
            bpm = beats.bpm,
        )
    }

    /** Segments between silences. */
    fun voicedSegments(
        silences: List<SilenceRemover.SilenceRange>,
        durationUs: Long,
    ): List<LongRange> {
        val sorted = silences.sortedBy { it.startUs }
        val out = mutableListOf<LongRange>()
        var cursor = 0L
        for (silence in sorted) {
            if (silence.startUs > cursor) out += cursor..silence.startUs
            cursor = maxOf(cursor, silence.endUs)
        }
        if (cursor < durationUs) out += cursor..durationUs
        return out
    }

    /**
     * Picks one cut per [targetClipUs], snapped to the nearest beat within half a target clip.
     * Beats too close to an existing cut are skipped so clips never become slivers.
     */
    private fun beatSnappedCuts(
        beats: BeatDetector.BeatResult,
        durationUs: Long,
        targetClipUs: Long,
    ): List<Long> {
        if (beats.confidence < 0.3f) return regularCuts(durationUs, targetClipUs)
        val cuts = mutableListOf<Long>()
        var position = targetClipUs
        val snapWindow = targetClipUs / 2
        while (position < durationUs) {
            val nearest = beats.beatTimesUs.minByOrNull { abs(it - position) }
            val snapped = if (nearest != null && abs(nearest - position) <= snapWindow) nearest else position
            if (cuts.isEmpty() || snapped - cuts.last() >= targetClipUs / 2) {
                cuts += snapped
            }
            position += targetClipUs
        }
        return cuts
    }

    private fun regularCuts(durationUs: Long, targetClipUs: Long): List<Long> {
        if (targetClipUs <= 0L) return emptyList()
        val cuts = mutableListOf<Long>()
        var position = targetClipUs
        while (position < durationUs) {
            cuts += position
            position += targetClipUs
        }
        return cuts
    }
}
