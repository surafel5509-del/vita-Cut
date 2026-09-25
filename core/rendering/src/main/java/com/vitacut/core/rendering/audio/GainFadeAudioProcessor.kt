package com.vitacut.core.rendering.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Applies constant gain + fade-in/fade-out envelopes to 16-bit PCM.
 *
 * Position within the item is tracked from the number of samples consumed, so fades stay exact
 * regardless of buffer boundaries. A per-time gain lambda ([gainAt]) additionally supports
 * keyframed volume (evaluated with item-local time).
 */
class GainFadeAudioProcessor(
    private val volume: Float,
    private val fadeInUs: Long,
    private val fadeOutUs: Long,
    private val itemDurationUs: Long,
    private val gainAt: ((timeUs: Long) -> Float)? = null,
) : BaseAudioProcessor() {

    private var processedSamplesPerChannel = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val channels = max(1, inputAudioFormat.channelCount)
        val sampleRate = max(1, inputAudioFormat.sampleRate)
        val frameCount = remaining / 2

        val output = replaceOutputBuffer(remaining)
        output.order(ByteOrder.LITTLE_ENDIAN)
        val input = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)

        var frameIndex = 0
        while (input.remaining() >= 2) {
            val sample = input.short.toInt()
            val channelIndex = frameIndex % channels
            val sampleTimeUs = if (channelIndex == 0) {
                processedSamplesPerChannel * 1_000_000L / sampleRate
            } else {
                (processedSamplesPerChannel - 1).coerceAtLeast(0) * 1_000_000L / sampleRate
            }
            val gain = gainFor(sampleTimeUs)
            val processed = (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output.putShort(processed.toShort())
            if (channelIndex == channels - 1) processedSamplesPerChannel++
            frameIndex++
        }
        output.flip()
    }

    private fun gainFor(timeUs: Long): Float {
        var gain = volume.coerceIn(0f, 1f)
        if (fadeInUs > 0 && timeUs < fadeInUs) {
            gain *= timeUs.toFloat() / fadeInUs
        }
        if (fadeOutUs > 0 && itemDurationUs > fadeOutUs) {
            val fadeStart = itemDurationUs - fadeOutUs
            if (timeUs > fadeStart) {
                gain *= max(0f, (itemDurationUs - timeUs).toFloat() / fadeOutUs)
            }
        }
        gainAt?.let { gain *= it(timeUs).coerceIn(0f, 1f) }
        return gain.coerceIn(0f, 1f)
    }

    override fun onFlush() {
        processedSamplesPerChannel = 0L
    }

    override fun onReset() {
        processedSamplesPerChannel = 0L
    }
}
