package com.vitacut.core.ai.beat

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Tempo & beat estimation from a normalized peak-energy envelope (as produced by
 * `WaveformExtractor`). Pure Kotlin → deterministic and unit-testable.
 *
 * Pipeline:
 * 1. **Onset flux** — positive first difference of the envelope approximates spectral flux for
 *    percussive music, which is where beat energy lives.
 * 2. **Adaptive peak picking** — an onset counts as a beat when it exceeds a local moving
 *    average by [Config.sensitivity] and respects the minimum spacing implied by
 *    [Config.maxBpm].
 * 3. **Tempo** — inter-beat intervals are folded into the [Config.minBpm]..[Config.maxBpm]
 *    range and the modal interval wins; [BeatResult.confidence] is the share of intervals
 *    consistent with that tempo (±12%).
 *
 * This is deliberately envelope-based rather than FFT-based: it needs no PCM access at analysis
 * time (the waveform is already cached for the timeline UI) and is fast enough for phones in
 * any performance mode.
 */
object BeatDetector {

    data class BeatResult(
        val beatTimesUs: List<Long>,
        val bpm: Float,
        val confidence: Float,
    ) {
        companion object {
            val EMPTY = BeatResult(emptyList(), 0f, 0f)
        }
    }

    data class Config(
        val minBpm: Float = 70f,
        val maxBpm: Float = 180f,
        /** Onset must exceed localMean * sensitivity. */
        val sensitivity: Float = 1.6f,
        /** Half-width of the moving-average window, in buckets. */
        val localWindow: Int = 10,
        /** Envelope floor below which nothing counts as an onset. */
        val noiseFloor: Float = 0.02f,
    )

    fun detect(peaks: FloatArray, bucketUs: Long, config: Config = Config()): BeatResult {
        if (peaks.size < 8 || bucketUs <= 0L) return BeatResult.EMPTY

        // 1. Onset flux.
        val flux = FloatArray(peaks.size)
        for (i in 1 until peaks.size) {
            flux[i] = max(0f, peaks[i] - peaks[i - 1])
        }

        // 2. Adaptive peak picking with refractory period.
        val minSpacingBuckets = max(
            1,
            (60_000_000.0 / (config.maxBpm * bucketUs)).roundToInt(),
        )
        val beats = mutableListOf<Int>()
        var lastBeat = -minSpacingBuckets * 2
        for (i in 1 until flux.size - 1) {
            if (peaks[i] < config.noiseFloor) continue
            var sum = 0f
            var count = 0
            for (j in (i - config.localWindow)..(i + config.localWindow)) {
                if (j in flux.indices) {
                    sum += flux[j]
                    count++
                }
            }
            val localMean = if (count > 0) sum / count else 0f
            val isLocalMax = flux[i] >= flux[i - 1] && flux[i] > flux[i + 1]
            if (isLocalMax && flux[i] > localMean * config.sensitivity && flux[i] > config.noiseFloor &&
                i - lastBeat >= minSpacingBuckets
            ) {
                beats += i
                lastBeat = i
            }
        }
        if (beats.size < 3) return BeatResult.EMPTY

        // 3. Tempo from folded inter-beat intervals.
        val minIsiUs = (60_000_000.0 / config.maxBpm).toLong()
        val maxIsiUs = (60_000_000.0 / config.minBpm).toLong()
        val intervals = beats.zipWithNext { a, b -> (b - a).toLong() * bucketUs }
        val folded = intervals.map { foldIsi(it, minIsiUs, maxIsiUs) }
        val tempo = modalValue(folded, toleranceUs = minIsiUs / 8)
        if (tempo == null) {
            // Irregular onsets (speech, ambient): report beats without a tempo claim.
            return BeatResult(beats.map { it * bucketUs }, 0f, 0f)
        }
        val consistent = folded.count { abs(it - tempo) <= tempo * 0.12f }
        val confidence = consistent.toFloat() / folded.size
        val bpm = (60_000_000.0 / tempo).toFloat()

        // Re-anchor beats on a regular grid from the median phase when confidence is high,
        // smoothing small human/quantization jitter.
        val times = if (confidence >= 0.6f) {
            quantize(beats.map { it * bucketUs }, tempo)
        } else {
            beats.map { it * bucketUs }
        }
        return BeatResult(times, bpm, confidence)
    }

    /** Folds an interval into the tempo range by halving/doubling. */
    private fun foldIsi(isiUs: Long, minIsiUs: Long, maxIsiUs: Long): Long {
        var value = isiUs
        while (value > maxIsiUs && value / 2 >= minIsiUs) value /= 2
        while (value < minIsiUs && value * 2 <= maxIsiUs) value *= 2
        return value
    }

    /** Crude 1-D mode: the value with the most neighbors within [toleranceUs]. */
    private fun modalValue(values: List<Long>, toleranceUs: Long): Long? {
        if (values.isEmpty()) return null
        var best: Long? = null
        var bestCount = 0
        for (candidate in values) {
            val count = values.count { abs(it - candidate) <= toleranceUs }
            if (count > bestCount) {
                bestCount = count
                best = candidate
            }
        }
        // Require at least 30% support to claim a tempo.
        return if (bestCount >= max(2, (values.size * 0.3f).toInt())) best else null
    }

    /** Snaps each beat to the nearest grid point of period [periodUs], keeping monotonic order. */
    private fun quantize(timesUs: List<Long>, periodUs: Long): List<Long> {
        if (timesUs.isEmpty() || periodUs <= 0L) return timesUs
        val anchor = timesUs[timesUs.size / 2]
        val out = mutableListOf<Long>()
        var last = Long.MIN_VALUE
        for (t in timesUs) {
            val k = ((t - anchor).toFloat() / periodUs).roundToInt()
            val snapped = anchor + k * periodUs
            if (snapped > last) {
                out += snapped
                last = snapped
            }
        }
        return out
    }

    /** Beat times restricted to a window — used by auto-cut. */
    fun beatsInRange(result: BeatResult, startUs: Long, endUs: Long): List<Long> =
        result.beatTimesUs.filter { it in startUs..endUs }
}

/** Small helper so callers don't hardcode µs math. */
fun bpmToIntervalUs(bpm: Float): Long =
    if (bpm <= 0f) 0L else min(Long.MAX_VALUE, (60_000_000.0 / bpm).toLong())
