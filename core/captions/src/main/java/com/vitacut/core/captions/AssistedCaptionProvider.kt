package com.vitacut.core.captions

import android.net.Uri
import com.vitacut.core.common.logging.VitaLog
import com.vitacut.core.common.result.VitaError
import com.vitacut.core.common.result.VitaResult
import com.vitacut.core.media.waveform.WaveformExtractor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline fallback transcription provider (spec: AI features degrade gracefully).
 *
 * No neural STT model ships with the app, so instead of failing, this provider uses
 * [SpeechSegmenter] to find *when* speech happens and returns correctly-timed, empty-text cues
 * flagged [Transcript.needsReview]. The caption editor presents them as "review & type" boxes —
 * the user still gets 80% of auto-caption value (timing, splitting, karaoke word pacing via
 * [CaptionEditor.estimateWordTimings]) with zero network and zero on-device ML.
 *
 * If a real STT engine is later bundled, it is added to the Hilt provider set with a lower
 * [priority] and this remains the safety net.
 */
@Singleton
class AssistedCaptionProvider @Inject constructor(
    private val waveformExtractor: WaveformExtractor,
) : TranscriptionProvider {

    override val id: String = "assisted-segmentation"
    override val displayNameKey: String = "captions_engine_assisted"
    override val priority: Int = 1000 // always last: it is the fallback

    override suspend fun isAvailable(): Boolean = true // pure local DSP

    override suspend fun transcribeAudio(
        uri: Uri,
        languageTag: String,
        onProgress: (Float) -> Unit,
    ): VitaResult<Transcript> = try {
        val waveform = waveformExtractor.extract(uri, buckets = SEGMENT_BUCKETS, onProgress)
        if (waveform.isEmpty || waveform.durationUs <= 0L) {
            return VitaResult.Failure(VitaError.UnsupportedMedia("no-audio-track"))
        }
        val bucketUs = waveform.durationUs / waveform.peaks.size.coerceAtLeast(1)
        val segments = SpeechSegmenter.segment(waveform.peaks, bucketUs)
        onProgress(1f)
        if (segments.isEmpty()) {
            return VitaResult.Failure(VitaError.CapabilityUnavailable("captions_no_speech_detected"))
        }
        VitaResult.Success(
            Transcript(
                languageTag = languageTag,
                cues = segments.map { TranscriptCue(it.startUs, it.endUs, text = "") },
                engineId = id,
                needsReview = true,
            ),
        )
    } catch (t: Throwable) {
        VitaLog.w(TAG, "Assisted transcription failed: ${t.message}")
        VitaResult.Failure(VitaError.CorruptMedia(t.message ?: "transcription"))
    }

    companion object {
        private const val TAG = "AssistedCaptions"
        private const val SEGMENT_BUCKETS = 1024
    }
}
