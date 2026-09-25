package com.vitacut.core.ai.silence

import kotlin.math.max

/**
 * Silence detection over a normalized peak envelope — the analysis half of "remove silences".
 * Pure Kotlin; the timeline engine performs the actual cuts so undo/redo and ripple behavior
 * stay in one place.
 *
 * Threshold is adaptive: a fraction of the loud percentile, with an absolute floor so fully
 * quiet files don't produce "everything is silence" results.
 */
object SilenceRemover {

    data class SilenceRange(val startUs: Long, val endUs: Long) {
        val durationUs: Long get() = endUs - startUs
    }

    data class Config(
        /** Threshold = max(absoluteFloor, percentile90 * thresholdFraction). */
        val thresholdFraction: Float = 0.12f,
        val absoluteFloor: Float = 0.015f,
        /** Silences shorter than this are kept (natural pauses). */
        val minSilenceUs: Long = 700_000L,
        /** Kept at each silence edge so cuts don't clip consonants. */
        val keepPaddingUs: Long = 120_000L,
        /** When true, leading/trailing silences are ignored (intros/outros are intentional). */
        val ignoreEdges: Boolean = true,
    )

    fun findSilences(peaks: FloatArray, bucketUs: Long, config: Config = Config()): List<SilenceRange> {
        if (peaks.isEmpty() || bucketUs <= 0L) return emptyList()
        val threshold = max(config.absoluteFloor, percentile90(peaks) * config.thresholdFraction)
        val totalUs = peaks.size.toLong() * bucketUs

        val ranges = mutableListOf<SilenceRange>()
        var silenceStart = -1
        for (i in peaks.indices) {
            val silent = peaks[i] < threshold
            if (silent && silenceStart < 0) {
                silenceStart = i
            } else if (!silent && silenceStart >= 0) {
                ranges += SilenceRange(silenceStart * bucketUs, i * bucketUs)
                silenceStart = -1
            }
        }
        if (silenceStart >= 0) ranges += SilenceRange(silenceStart * bucketUs, totalUs)

        return ranges
            .filter { it.durationUs >= config.minSilenceUs }
            // Whole-file silence is a degenerate result, never "removable".
            .filterNot { it.startUs == 0L && it.endUs >= totalUs }
            // Intros/outros are intentional; edge silences stay unless the caller opts in.
            .filterNot { config.ignoreEdges && (it.startUs == 0L || it.endUs >= totalUs - bucketUs) }
            .map { range ->
                SilenceRange(
                    startUs = (range.startUs + config.keepPaddingUs).coerceAtMost(range.endUs),
                    endUs = (range.endUs - config.keepPaddingUs).coerceAtLeast(range.startUs),
                )
            }
            .filter { it.endUs > it.startUs && it.durationUs > 0L }
    }

    /** Total removable duration — surfaced in the UI before applying. */
    fun removableDurationUs(ranges: List<SilenceRange>): Long = ranges.sumOf { it.durationUs }

    private fun percentile90(values: FloatArray): Float {
        val sorted = values.copyOf().also { it.sort() }
        return sorted[((sorted.size - 1) * 0.9f).toInt().coerceIn(0, sorted.size - 1)]
    }
}
