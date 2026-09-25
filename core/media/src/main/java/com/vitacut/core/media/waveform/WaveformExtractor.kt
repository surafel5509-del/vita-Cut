package com.vitacut.core.media.waveform

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.vitacut.core.common.logging.VitaLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.math.sqrt
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracts an audio waveform (peak + RMS buckets) from any decodable audio/video file.
 *
 * Real decode path: [MediaExtractor] → [MediaCodec] → 16-bit PCM → bucketed peaks. Used for the
 * timeline waveform strips, silence detection (AI tools) and the voiceover recorder preview.
 * Runs on [Dispatchers.IO]-style background threads and supports cancellation.
 */
@Singleton
class WaveformExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * @param buckets number of output samples across the whole file (timeline width in cells)
     * @return normalized peak values 0..1 per bucket, empty list when the file has no audio
     */
    suspend fun extract(
        uri: Uri,
        buckets: Int,
        onProgress: (Float) -> Unit = {},
    ): WaveformData = withContext(Dispatchers.IO) {
        val peaks = FloatArray(buckets.coerceIn(8, 4096))
        val counts = IntArray(peaks.size)
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)
            val trackIndex = selectAudioTrack(extractor) ?: return@withContext WaveformData.EMPTY
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext WaveformData.EMPTY
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else 0L

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var inputDone = false
            var outputDone = false
            var sawOutputEos = false
            var retryCount = 0
            val bufferInfo = MediaCodec.BufferInfo()

            while (!outputDone) {
                currentCoroutineContext().ensureActive()

                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val inputBuffer = codec.getInputBuffer(index)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outputIndex >= 0 -> {
                        if (bufferInfo.size > 0) {
                            val pcm = codec.getOutputBuffer(outputIndex)!!
                            pcm.position(bufferInfo.offset)
                            pcm.limit(bufferInfo.offset + bufferInfo.size)
                            accumulate(pcm, bufferInfo.presentationTimeUs, durationUs, peaks, counts)
                            if (durationUs > 0) {
                                onProgress((bufferInfo.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEos = true
                            outputDone = true
                        }
                    }

                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inputDone && !sawOutputEos) {
                            // Give the codec a few more tries before declaring completion.
                            if (retryCount++ > MAX_RETRIES) outputDone = true
                        }
                    }
                }
            }
            codec.stop()
            codec.release()
            codec = null

            val normalized = normalize(peaks, counts)
            WaveformData(
                peaks = normalized,
                sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                } else 44_100,
                channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else 2,
                durationUs = durationUs,
            )
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            VitaLog.w("WaveformExtractor", "Waveform extraction failed for $uri: ${t.message}")
            WaveformData.EMPTY
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
        }
    }

    private fun accumulate(
        pcm: ByteBuffer,
        presentationTimeUs: Long,
        durationUs: Long,
        peaks: FloatArray,
        counts: IntArray,
    ) {
        if (durationUs <= 0) return
        val shortBuffer: ShortBuffer = pcm.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var max = 0
        var sumSquares = 0L
        var n = 0
        while (shortBuffer.hasRemaining()) {
            val sample = shortBuffer.get().toInt()
            val magnitude = abs(sample)
            if (magnitude > max) max = magnitude
            sumSquares += sample.toLong() * sample
            n++
        }
        if (n == 0) return
        val bucket = ((presentationTimeUs.toDouble() / durationUs) * peaks.size)
            .toInt().coerceIn(0, peaks.size - 1)
        val peakValue = max / 32768f
        // Keep the max peak per bucket; counts track how many buffers contributed (for RMS mix).
        val rms = sqrt(sumSquares.toDouble() / n).toFloat() / 32768f
        val combined = peakValue * 0.75f + rms * 0.25f
        if (combined > peaks[bucket] || counts[bucket] == 0) {
            peaks[bucket] = maxOf(peaks[bucket], combined)
        }
        counts[bucket]++
    }

    private fun normalize(peaks: FloatArray, counts: IntArray): FloatArray {
        // Fill buckets that received no buffer (very short buckets at high zoom-out) by
        // interpolating from neighbors so the waveform has no holes.
        var lastValue = 0f
        for (i in peaks.indices) {
            if (counts[i] == 0 && i > 0) peaks[i] = lastValue * 0.9f
            lastValue = peaks[i]
        }
        val max = peaks.maxOrNull() ?: 0f
        if (max <= 0f) return peaks
        // Normalize to 0..1 with a gentle ceiling so quiet recordings stay visible.
        val gain = (0.95f / max).coerceAtMost(8f)
        return FloatArray(peaks.size) { (peaks[it] * gain).coerceIn(0f, 1f) }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
        private const val MAX_RETRIES = 20
    }
}

/** Normalized waveform ready for UI rendering and DSP analysis. */
data class WaveformData(
    val peaks: FloatArray,
    val sampleRate: Int,
    val channelCount: Int,
    val durationUs: Long,
) {
    val isEmpty: Boolean get() = peaks.isEmpty()

    /** Peak value at a normalized position 0..1. */
    fun peakAt(fraction: Float): Float {
        if (peaks.isEmpty()) return 0f
        val index = (fraction.coerceIn(0f, 1f) * (peaks.size - 1)).toInt()
        return peaks[index]
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WaveformData) return false
        return peaks.contentEquals(other.peaks) && sampleRate == other.sampleRate &&
            channelCount == other.channelCount && durationUs == other.durationUs
    }

    override fun hashCode(): Int {
        var result = peaks.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channelCount
        result = 31 * result + durationUs.hashCode()
        return result
    }

    companion object {
        val EMPTY = WaveformData(FloatArray(0), 44_100, 2, 0L)
    }
}
