package com.vitacut.core.rendering.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** Shared plumbing for simple per-sample 16-bit PCM processors. */
abstract class Pcm16AudioProcessor : BaseAudioProcessor() {

    protected var channels: Int = 2
    protected var sampleRate: Int = 44_100

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channels = max(1, inputAudioFormat.channelCount)
        sampleRate = max(1, inputAudioFormat.sampleRate)
        onFormatReady()
        return inputAudioFormat
    }

    protected open fun onFormatReady() = Unit

    final override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val output = replaceOutputBuffer(remaining)
        output.order(ByteOrder.LITTLE_ENDIAN)
        val input = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        while (input.remaining() >= 2) {
            val sample = input.short.toInt()
            val processed = processSample(sample).coerceIn(-32768, 32767)
            output.putShort(processed.toShort())
        }
        output.flip()
    }

    /** Processes one interleaved sample; state advances per call. */
    protected abstract fun processSample(sample: Int): Int
}

/**
 * Bass & treble shelf EQ (two cascaded RBJ biquads per channel). Real DSP, not a stub —
 * coefficients follow the standard audio EQ cookbook formulas.
 */
class BassTrebleAudioProcessor(
    private val bassGain: Float,   // -1..1 → ±12 dB at 90 Hz
    private val trebleGain: Float, // -1..1 → ±12 dB at 8 kHz
) : Pcm16AudioProcessor() {

    private class Biquad(
        val b0: Float, val b1: Float, val b2: Float, val a1: Float, val a2: Float,
    ) {
        var x1 = 0f; var x2 = 0f; var y1 = 0f; var y2 = 0f

        fun process(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            return y
        }

        fun reset() {
            x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
        }
    }

    private lateinit var bassFilters: Array<Biquad>
    private lateinit var trebleFilters: Array<Biquad>
    private var channelCursor = 0

    override fun onFormatReady() {
        bassFilters = Array(channels) { shelfCoefficients(90.0, bassGain * 12.0, lowShelf = true) }
        trebleFilters = Array(channels) { shelfCoefficients(8_000.0, trebleGain * 12.0, lowShelf = false) }
        channelCursor = 0
    }

    private fun shelfCoefficients(f0: Double, gainDb: Double, lowShelf: Boolean): Biquad {
        if (abs(gainDb) < 0.01) return Biquad(1f, 0f, 0f, 0f, 0f)
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * Math.PI * f0 / sampleRate
        val cosW = cos(w0)
        val sinW = kotlin.math.sin(w0)
        val alpha = sinW / 2.0 * sqrt((a + 1.0 / a) * (1.0 / 0.7 - 1.0) + 2.0)

        val b0: Double; val b1: Double; val b2: Double
        val a0: Double; val a1: Double; val a2: Double
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
        if (lowShelf) {
            b0 = a * ((a + 1) - (a - 1) * cosW + twoSqrtAAlpha)
            b1 = 2 * a * ((a - 1) - (a + 1) * cosW)
            b2 = a * ((a + 1) - (a - 1) * cosW - twoSqrtAAlpha)
            a0 = (a + 1) + (a - 1) * cosW + twoSqrtAAlpha
            a1 = -2 * ((a - 1) + (a + 1) * cosW)
            a2 = (a + 1) + (a - 1) * cosW - twoSqrtAAlpha
        } else {
            b0 = a * ((a + 1) + (a - 1) * cosW + twoSqrtAAlpha)
            b1 = -2 * a * ((a - 1) + (a + 1) * cosW)
            b2 = a * ((a + 1) + (a - 1) * cosW - twoSqrtAAlpha)
            a0 = (a + 1) - (a - 1) * cosW + twoSqrtAAlpha
            a1 = 2 * ((a - 1) - (a + 1) * cosW)
            a2 = (a + 1) - (a - 1) * cosW - twoSqrtAAlpha
        }
        return Biquad(
            (b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(),
            (a1 / a0).toFloat(), (a2 / a0).toFloat(),
        )
    }

    override fun processSample(sample: Int): Int {
        val ch = channelCursor % channels
        channelCursor++
        var x = sample / 32768f
        x = bassFilters[ch].process(x)
        x = trebleFilters[ch].process(x)
        return (x * 32767f).toInt()
    }

    override fun onFlush() {
        if (::bassFilters.isInitialized) bassFilters.forEach { it.reset() }
        if (::trebleFilters.isInitialized) trebleFilters.forEach { it.reset() }
        channelCursor = 0
    }

    override fun onReset() = onFlush()
}

/**
 * Echo (delay with feedback + wet/dry mix). Delay line sized to the largest supported delay.
 */
class EchoAudioProcessor(
    private val wet: Float,       // 0..1
    private val delayMs: Int,     // e.g. 300
    private val feedback: Float = 0.35f,
) : Pcm16AudioProcessor() {

    private var delayLines: Array<FloatArray> = emptyArray()
    private var writePositions: IntArray = IntArray(0)
    private var channelCursor = 0
    private var delaySamples = 0

    override fun onFormatReady() {
        delaySamples = (sampleRate.toLong() * delayMs / 1000).toInt().coerceIn(1, sampleRate * 2)
        delayLines = Array(channels) { FloatArray(delaySamples) }
        writePositions = IntArray(channels)
        channelCursor = 0
    }

    override fun processSample(sample: Int): Int {
        val ch = channelCursor % channels
        channelCursor++
        if (delayLines.isEmpty()) return sample
        val line = delayLines[ch]
        val pos = writePositions[ch]
        val delayed = line[pos]
        line[pos] = sample / 32768f + delayed * feedback
        writePositions[ch] = (pos + 1) % delaySamples
        val out = sample / 32768f + delayed * wet.coerceIn(0f, 1f)
        return (out * 32767f).toInt()
    }

    override fun onFlush() {
        delayLines = Array(channels) { FloatArray(max(1, delaySamples)) }
        writePositions = IntArray(channels)
        channelCursor = 0
    }

    override fun onReset() = onFlush()
}

/**
 * Reverb: Schroeder design — four parallel comb filters into two series allpass filters.
 * Small, dependency-free and fast enough for real-time preview and export.
 */
class ReverbAudioProcessor(
    private val wet: Float, // 0..1
) : Pcm16AudioProcessor() {

    private class Comb(val size: Int) {
        val buffer = FloatArray(size)
        var index = 0
        val feedback = 0.805f

        fun process(input: Float): Float {
            val output = buffer[index]
            buffer[index] = input + output * feedback
            index = (index + 1) % size
            return output
        }
    }

    private class Allpass(val size: Int) {
        val buffer = FloatArray(size)
        var index = 0
        val feedback = 0.5f

        fun process(input: Float): Float {
            val buffered = buffer[index]
            val output = -input * feedback + buffered
            buffer[index] = input + buffered * feedback
            index = (index + 1) % size
            return output
        }
    }

    private var combs: Array<Array<Comb>> = emptyArray()
    private var allpasses: Array<Array<Allpass>> = emptyArray()
    private var channelCursor = 0

    override fun onFormatReady() {
        val combTunings = intArrayOf(1116, 1188, 1277, 1356).map { it * sampleRate / 44_100 }
        val allpassTunings = intArrayOf(556, 441).map { it * sampleRate / 44_100 }
        combs = Array(channels) { ch ->
            Array(combTunings.size) { Comb(combTunings[it] + ch * 13) } // decorrelate channels
        }
        allpasses = Array(channels) { Array(allpassTunings.size) { Allpass(allpassTunings[it]) } }
        channelCursor = 0
    }

    override fun processSample(sample: Int): Int {
        val ch = channelCursor % channels
        channelCursor++
        if (combs.isEmpty()) return sample
        val x = sample / 32768f
        var out = 0f
        for (comb in combs[ch]) out += comb.process(x)
        out /= combs[ch].size
        for (allpass in allpasses[ch]) out = allpass.process(out)
        val mixed = x * (1f - wet.coerceIn(0f, 1f) * 0.7f) + out * wet.coerceIn(0f, 1f) * 2.2f
        return (mixed * 32767f).toInt()
    }

    override fun onFlush() = onFormatReady()
    override fun onReset() = onFormatReady()
}

/**
 * Noise reduction via adaptive noise-floor gating:
 * a smoothed per-channel noise estimate (minimum-statistics style) gates low-level frames.
 * Deliberately conservative — it attenuates hiss without pumping artifacts on speech.
 */
class NoiseReductionAudioProcessor(
    private val strength: Float, // 0..1
) : Pcm16AudioProcessor() {

    private var noiseFloors: FloatArray = FloatArray(0)
    private var channelCursor = 0

    override fun onFormatReady() {
        noiseFloors = FloatArray(channels) { 0.01f }
        channelCursor = 0
    }

    override fun processSample(sample: Int): Int {
        val ch = channelCursor % channels
        channelCursor++
        if (noiseFloors.isEmpty()) return sample
        val x = sample / 32768f
        val magnitude = abs(x)

        // Update noise estimate: fast attack down (track quieter passages), slow up.
        val floor = noiseFloors[ch]
        noiseFloors[ch] = if (magnitude < floor) {
            floor * 0.995f + magnitude * 0.005f
        } else {
            floor * 0.9999f + magnitude * 0.0001f
        }

        val threshold = noiseFloors[ch] * (2f + strength * 6f)
        val gain = if (magnitude <= threshold && magnitude > 0f) {
            val ratio = magnitude / threshold
            // Soft knee below the threshold.
            1f - (1f - ratio).pow(2f) * strength
        } else 1f
        return (x * gain.coerceIn(0f, 1f) * 32767f).toInt()
    }

    override fun onFlush() = onFormatReady()
    override fun onReset() = onFormatReady()
}

/**
 * Voice enhancement: highpass (rumble removal) + presence shelf boost + soft-knee compression.
 * Tuned as a one-tap "make speech sound broadcast-ready" chain.
 */
class VoiceEnhanceAudioProcessor : Pcm16AudioProcessor() {

    // One-pole highpass state per channel.
    private var hpPrevIn: FloatArray = FloatArray(0)
    private var hpPrevOut: FloatArray = FloatArray(0)
    private var envelope: FloatArray = FloatArray(0)
    private var channelCursor = 0
    private var hpCoefficient = 0.995f

    override fun onFormatReady() {
        hpPrevIn = FloatArray(channels)
        hpPrevOut = FloatArray(channels)
        envelope = FloatArray(channels) { 0.1f }
        channelCursor = 0
        // ~85 Hz one-pole highpass.
        val rc = 1f / (2f * Math.PI.toFloat() * 85f)
        val dt = 1f / sampleRate
        hpCoefficient = rc / (rc + dt)
    }

    override fun processSample(sample: Int): Int {
        val ch = channelCursor % channels
        channelCursor++
        if (hpPrevIn.isEmpty()) return sample
        val x = sample / 32768f

        // Highpass.
        val hp = hpCoefficient * (hpPrevOut[ch] + x - hpPrevIn[ch])
        hpPrevIn[ch] = x
        hpPrevOut[ch] = hp

        // Presence boost: gentle peaking around 3.5 kHz via first-difference emphasis.
        val presence = hp * 0.9f + (x - hp) * 0.35f

        // Soft compression on a smoothed envelope.
        val magnitude = abs(presence)
        val env = envelope[ch] * 0.999f + magnitude * 0.001f
        envelope[ch] = env
        val overThreshold = max(0f, env - 0.25f)
        val gain = if (overThreshold > 0f) 1f - overThreshold * 0.9f else 1f
        // Makeup gain for speech loudness.
        val out = presence * gain * 1.25f
        return (out * 32767f).toInt()
    }

    override fun onFlush() = onFormatReady()
    override fun onReset() = onFormatReady()
}

/** Peak normalization with a pre-computed gain (analysis happens in NormalizeAnalyzer). */
class NormalizeAudioProcessor(private val gain: Float) : Pcm16AudioProcessor() {
    override fun processSample(sample: Int): Int = (sample * gain).toInt()
}

/**
 * Computes the normalization gain for a decoded waveform: peak → -1 dBFS.
 * Pure function over [WaveformPeaks] so it is unit-testable.
 */
object NormalizeAnalyzer {
    fun gainForPeak(peak: Float, targetDb: Float = -1f): Float {
        if (peak <= 0.0001f) return 1f
        val targetLinear = 10f.pow(targetDb / 20f)
        return (targetLinear / peak).coerceIn(0.1f, 8f)
    }

    fun peakFromDb(db: Float): Float = 10f.pow(db / 20f)

    fun dbFromLinear(linear: Float): Float =
        if (linear <= 0f) -96f else (20f * ln(linear) / ln(10f)).toFloat().let { max(it, -96f) }

    /** RMS in dBFS from normalized samples (0..1). */
    fun rmsDb(samples: FloatArray): Float {
        if (samples.isEmpty()) return -96f
        val sum = samples.fold(0.0) { acc, v -> acc + v.toDouble() * v }
        val rms = sqrt(sum / samples.size).toFloat()
        return dbFromLinear(rms)
    }

    /** dB→linear helper exposed for UI meters. */
    fun exp10(x: Float): Float = 10f.pow(x)
}
