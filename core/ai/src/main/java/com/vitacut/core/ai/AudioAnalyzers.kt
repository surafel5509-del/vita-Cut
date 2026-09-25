package com.vitacut.core.ai

import android.net.Uri
import com.vitacut.core.ai.beat.AutoCutAnalyzer
import com.vitacut.core.ai.beat.BeatDetector
import com.vitacut.core.ai.silence.SilenceRemover
import com.vitacut.core.common.result.VitaError
import com.vitacut.core.common.result.VitaResult
import com.vitacut.core.media.waveform.WaveformExtractor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the pure analyzers ([BeatDetector], [SilenceRemover], [AutoCutAnalyzer]) to media
 * files via the cached waveform envelope. One envelope extraction powers all three features,
 * so the editor runs them without re-decoding audio.
 */
@Singleton
class AudioAnalyzers @Inject constructor(
    private val waveformExtractor: WaveformExtractor,
) {

    private suspend fun envelope(uri: Uri, buckets: Int) =
        waveformExtractor.extract(uri, buckets)

    suspend fun detectBeats(
        uri: Uri,
        config: BeatDetector.Config = BeatDetector.Config(),
    ): VitaResult<BeatDetector.BeatResult> = runCatching {
        val waveform = envelope(uri, BEAT_BUCKETS)
        if (waveform.isEmpty || waveform.durationUs <= 0L) {
            return VitaResult.Failure(VitaError.UnsupportedMedia("no-audio-track"))
        }
        val bucketUs = waveform.durationUs / waveform.peaks.size
        VitaResult.Success(BeatDetector.detect(waveform.peaks, bucketUs, config))
    }.getOrElse { VitaResult.Failure(VitaError.CorruptMedia(it.message ?: "beats")) }

    suspend fun detectSilences(
        uri: Uri,
        config: SilenceRemover.Config = SilenceRemover.Config(),
    ): VitaResult<List<SilenceRemover.SilenceRange>> = runCatching {
        val waveform = envelope(uri, BEAT_BUCKETS)
        if (waveform.isEmpty || waveform.durationUs <= 0L) {
            return VitaResult.Failure(VitaError.UnsupportedMedia("no-audio-track"))
        }
        val bucketUs = waveform.durationUs / waveform.peaks.size
        VitaResult.Success(SilenceRemover.findSilences(waveform.peaks, bucketUs, config))
    }.getOrElse { VitaResult.Failure(VitaError.CorruptMedia(it.message ?: "silences")) }

    /** One envelope → beats + silences → the auto-cut plan shown in the "Auto edit" sheet. */
    suspend fun autoCutPlan(
        uri: Uri,
        durationUs: Long,
        config: AutoCutAnalyzer.AutoCutConfig = AutoCutAnalyzer.AutoCutConfig(),
        beatConfig: BeatDetector.Config = BeatDetector.Config(),
        silenceConfig: SilenceRemover.Config = SilenceRemover.Config(),
    ): VitaResult<AutoCutAnalyzer.CutPlan> = runCatching {
        val waveform = envelope(uri, BEAT_BUCKETS)
        if (waveform.isEmpty || waveform.durationUs <= 0L) {
            return VitaResult.Failure(VitaError.UnsupportedMedia("no-audio-track"))
        }
        val bucketUs = waveform.durationUs / waveform.peaks.size
        val beats = BeatDetector.detect(waveform.peaks, bucketUs, beatConfig)
        val silences = SilenceRemover.findSilences(waveform.peaks, bucketUs, silenceConfig)
        VitaResult.Success(AutoCutAnalyzer.plan(beats, silences, durationUs, config))
    }.getOrElse { VitaResult.Failure(VitaError.CorruptMedia(it.message ?: "auto-cut")) }

    private companion object {
        /** ~5ms buckets for a 10-minute file — plenty for beat/silence resolution. */
        const val BEAT_BUCKETS = 4096
    }
}
