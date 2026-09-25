package com.vitacut.core.rendering.audio

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SpeedProvider
import com.vitacut.core.model.AudioClipItem
import com.vitacut.core.model.AudioEffects
import com.vitacut.core.model.SpeedCurve
import com.vitacut.core.model.SpeedModel
import com.vitacut.core.model.VideoClipItem
import com.vitacut.core.timeline.SpeedMath

/**
 * Builds the list of [AudioProcessor]s for a clip's audio graph, in the canonical order:
 * voice enhance → noise reduction → EQ → echo → reverb → normalize → gain/fades.
 *
 * Identical chains are used by preview players and the export [Effects], which keeps the audio
 * you hear while editing byte-equivalent in spirit to the exported mix.
 */
object AudioEffectsFactory {

    fun forVideoClip(clip: VideoClipItem, keyframeGainAt: ((Long) -> Float)? = null): List<AudioProcessor> {
        val processors = mutableListOf<AudioProcessor>()
        if (clip.muted) return listOf(GainFadeAudioProcessor(0f, 0L, 0L, clip.durationUs))
        processors += GainFadeAudioProcessor(
            volume = clip.volume,
            fadeInUs = clip.fadeInUs,
            fadeOutUs = clip.fadeOutUs,
            itemDurationUs = clip.durationUs,
            gainAt = keyframeGainAt,
        )
        return processors
    }

    fun forAudioClip(clip: AudioClipItem, keyframeGainAt: ((Long) -> Float)? = null): List<AudioProcessor> {
        if (clip.muted) return listOf(GainFadeAudioProcessor(0f, 0L, 0L, clip.durationUs))
        val fx = clip.audioEffects
        val processors = mutableListOf<AudioProcessor>()
        if (fx.voiceEnhance) processors += VoiceEnhanceAudioProcessor()
        if (fx.noiseReduction > 0f) processors += NoiseReductionAudioProcessor(fx.noiseReduction)
        if (fx.bass != 0f || fx.treble != 0f) processors += BassTrebleAudioProcessor(fx.bass, fx.treble)
        if (fx.echo > 0f) processors += EchoAudioProcessor(fx.echo, fx.echoDelayMs.coerceIn(40, 1200))
        if (fx.reverb > 0f) processors += ReverbAudioProcessor(fx.reverb)
        if (fx.normalize) processors += NormalizeAudioProcessor(fx.normalizeGain)
        processors += GainFadeAudioProcessor(
            volume = clip.volume,
            fadeInUs = clip.fadeInUs,
            fadeOutUs = clip.fadeOutUs,
            itemDurationUs = clip.durationUs,
            gainAt = keyframeGainAt,
        )
        return processors
    }

    fun isNeutral(fx: AudioEffects): Boolean = fx.isNeutral
}

/**
 * Adapts a [SpeedModel] to Media3's [SpeedProvider] for export.
 *
 * Times are *source* timestamps (that's what Transformer asks for). Constant speeds map to a
 * trivial provider; curves evaluate [SpeedCurve.speedAt] at the normalized source position.
 * Combined with `Effects.createExperimentalSpeedChangingEffect`, audio and video stay in sync
 * through ramps because Media3 interlinks both sides of the speed change.
 */
class VitaSpeedProvider(
    private val speedModel: SpeedModel,
    private val sourceInUs: Long,
    private val sourceOutUs: Long,
) : SpeedProvider {

    override fun getSpeed(timeUs: Long): PlaybackParameters {
        val speed = when (speedModel) {
            is SpeedModel.Constant -> speedModel.speed
            is SpeedModel.Curve -> {
                val sourceDuration = (sourceOutUs - sourceInUs).coerceAtLeast(1L)
                val progress = ((timeUs - sourceInUs).toFloat() / sourceDuration).coerceIn(0f, 1f)
                speedModel.curve.speedAt(progress)
            }
        }
        return PlaybackParameters(speed.coerceIn(0.1f, 8f))
    }

    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long {
        if (speedModel is SpeedModel.Constant) return Long.MAX_VALUE
        // Curves change continuously; report the next segment boundary for scheduler hints.
        val curve = speedModel.curve
        val sourceDuration = (sourceOutUs - sourceInUs).coerceAtLeast(1L)
        val progress = ((timeUs - sourceInUs).toFloat() / sourceDuration).coerceIn(0f, 1f)
        val nextPoint = curve.points.sortedBy { it.position }.firstOrNull { it.position > progress }
        return nextPoint?.let { sourceInUs + (it.position * sourceDuration).toLong() } ?: Long.MAX_VALUE
    }

    companion object {
        fun forClip(clip: VideoClipItem): VitaSpeedProvider =
            VitaSpeedProvider(clip.speed, clip.sourceInUs, clip.sourceOutUs)

        fun forAudioClip(clip: AudioClipItem): VitaSpeedProvider =
            VitaSpeedProvider(clip.speed, clip.sourceInUs, clip.sourceOutUs)

        /** Timeline duration helper reused from the engine (kept here for pipeline locality). */
        fun timelineDuration(clip: VideoClipItem): Long =
            SpeedMath.timelineDurationUs(clip.sourceInUs, clip.sourceOutUs, clip.speed)
    }
}
