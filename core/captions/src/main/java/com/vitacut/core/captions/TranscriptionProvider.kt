package com.vitacut.core.captions

import android.net.Uri
import com.vitacut.core.common.result.VitaResult
import com.vitacut.core.model.Caption
import com.vitacut.core.model.CaptionSet

/** One recognized cue with optional word-level timing. */
data class TranscriptCue(
    val startUs: Long,
    val endUs: Long,
    val text: String,
    val wordTimingsUs: List<Long> = emptyList(),
) {
    fun toCaption(): Caption = Caption(
        startUs = startUs,
        endUs = endUs,
        text = text,
        wordTimingsUs = wordTimingsUs,
    )
}

/**
 * The result of transcribing one audio source.
 *
 * [needsReview] is true when the engine could not produce text (offline fallback) — the cues are
 * timed segments the user confirms/fills in the caption editor, which is the honest degradation
 * path required by the spec instead of pretending a transcription exists.
 */
data class Transcript(
    val languageTag: String,
    val cues: List<TranscriptCue>,
    val engineId: String,
    val needsReview: Boolean = false,
) {
    fun toCaptionSet(style: com.vitacut.core.model.CaptionStyle = com.vitacut.core.model.CaptionStyle()): CaptionSet =
        CaptionSet(
            captions = cues.map { it.toCaption() },
            style = style,
            enabled = true,
            languageTag = languageTag,
        )
}

/**
 * Abstraction over speech-to-text engines.
 *
 * Implementations must never throw: all failures come back as [VitaResult.Failure] with a
 * localized message key. A real neural STT backend (on-device Whisper-class model or cloud
 * service) plugs in by adding another binding to the Hilt multibinding set — no call-site
 * changes. The bundled [AssistedCaptionProvider] guarantees captions always work offline.
 */
interface TranscriptionProvider {
    /** Stable identifier used for ordering & telemetry. */
    val id: String

    /** String-resource key for the engine's user-facing name. */
    val displayNameKey: String

    /** Lower is tried first by [TranscriptionRegistry]. */
    val priority: Int

    /** Cheap capability probe (service present, model downloaded, network policy, …). */
    suspend fun isAvailable(): Boolean

    /**
     * Transcribes the audio at [uri].
     * @param onProgress 0..1 progress reporting for long files.
     */
    suspend fun transcribeAudio(
        uri: Uri,
        languageTag: String,
        onProgress: (Float) -> Unit = {},
    ): VitaResult<Transcript>
}
