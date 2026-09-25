package com.vitacut.core.captions

import kotlin.math.max
import kotlin.math.min

/**
 * Energy-based speech segmentation over a normalized peak envelope (as produced by
 * `WaveformExtractor`).
 *
 * Pure Kotlin and deterministic → unit-testable and usable as the offline "assisted captions"
 * backbone: it finds *where* someone is talking even when no STT engine is available, so the
 * user gets correctly timed cue boxes to fill in instead of nothing.
 *
 * Algorithm: adaptive threshold with hysteresis (start above [Config.gateFraction] of the loud
 * percentile, end below 60% of it), gap merging, min/max duration enforcement, and splitting of
 * over-long runs at their quietest interior bucket.
 */
object SpeechSegmenter {

    data class Segment(val startUs: Long, val endUs: Long) {
        val durationUs: Long get() = endUs - startUs
    }

    data class Config(
        /** Threshold as a fraction of the 80th-percentile peak. */
        val gateFraction: Float = 0.30f,
        /** Never gate below this absolute peak (silence-floor noise immunity). */
        val absoluteFloor: Float = 0.04f,
        val minSegmentUs: Long = 500_000L,
        val maxSegmentUs: Long = 6_000_000L,
        /** Gaps shorter than this are swallowed (inter-word pauses). */
        val mergeGapUs: Long = 300_000L,
        /** Padding added on both ends of a segment. */
        val padUs: Long = 120_000L,
    )

    fun segment(peaks: FloatArray, bucketUs: Long, config: Config = Config()): List<Segment> {
        if (peaks.isEmpty() || bucketUs <= 0L) return emptyList()
        val threshold = max(config.absoluteFloor, percentile(peaks, 0.8f) * config.gateFraction)
        val releaseThreshold = threshold * 0.6f
        val totalUs = peaks.size.toLong() * bucketUs

        // Hysteresis run detection.
        val raw = mutableListOf<Segment>()
        var runStart = -1
        for (i in peaks.indices) {
            val active = if (runStart < 0) peaks[i] >= threshold else peaks[i] >= releaseThreshold
            if (active && runStart < 0) {
                runStart = i
            } else if (!active && runStart >= 0) {
                raw += Segment(runStart * bucketUs, i * bucketUs)
                runStart = -1
            }
        }
        if (runStart >= 0) raw += Segment(runStart * bucketUs, totalUs)

        // Merge across short gaps.
        val merged = mutableListOf<Segment>()
        for (segment in raw) {
            val previous = merged.lastOrNull()
            if (previous != null && segment.startUs - previous.endUs <= config.mergeGapUs) {
                merged[merged.size - 1] = previous.copy(endUs = segment.endUs)
            } else {
                merged += segment
            }
        }

        // Drop too-short runs, split too-long runs, pad, clamp.
        val result = mutableListOf<Segment>()
        for (segment in merged) {
            if (segment.durationUs < config.minSegmentUs) continue
            result += splitLong(segment, peaks, bucketUs, config)
        }
        return result.map {
            Segment(
                startUs = (it.startUs - config.padUs).coerceAtLeast(0L),
                endUs = min(it.endUs + config.padUs, totalUs),
            )
        }.filter { it.endUs > it.startUs }
    }

    private fun splitLong(
        segment: Segment,
        peaks: FloatArray,
        bucketUs: Long,
        config: Config,
    ): List<Segment> {
        if (segment.durationUs <= config.maxSegmentUs) return listOf(segment)
        val chunks = ((segment.durationUs + config.maxSegmentUs - 1) / config.maxSegmentUs).toInt()
        val approximateChunk = segment.durationUs / chunks
        val out = mutableListOf<Segment>()
        var cursor = segment.startUs
        for (c in 0 until chunks) {
            val nominalEnd = if (c == chunks - 1) segment.endUs else cursor + approximateChunk
            // Snap the boundary to the quietest bucket within ±20% of the nominal end.
            val end = if (c == chunks - 1) {
                nominalEnd
            } else {
                val window = (approximateChunk * 0.2).toLong().coerceAtLeast(bucketUs)
                quietestPoint(peaks, bucketUs, nominalEnd, window, cursor + config.minSegmentUs)
            }
            out += Segment(cursor, end)
            cursor = end
        }
        return out.filter { it.durationUs >= config.minSegmentUs }
    }

    private fun quietestPoint(
        peaks: FloatArray,
        bucketUs: Long,
        nominalUs: Long,
        windowUs: Long,
        floorUs: Long,
    ): Long {
        val fromBucket = ((nominalUs - windowUs).coerceAtLeast(floorUs) / bucketUs).toInt()
        val toBucket = ((nominalUs + windowUs) / bucketUs).toInt().coerceAtMost(peaks.size - 1)
        if (fromBucket > toBucket) return nominalUs
        var quietest = fromBucket
        for (i in fromBucket..toBucket) {
            if (peaks[i] < peaks[quietest]) quietest = i
        }
        return quietest.toLong() * bucketUs
    }

    private fun percentile(values: FloatArray, p: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.copyOf().also { it.sort() }
        val index = (p * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }
}
