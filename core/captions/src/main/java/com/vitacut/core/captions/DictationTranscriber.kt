package com.vitacut.core.captions

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.vitacut.core.common.logging.VitaLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Live dictation states for the caption text field's mic button. */
sealed interface DictationState {
    data object Listening : DictationState
    data class Partial(val text: String) : DictationState
    data class Final(val text: String) : DictationState
    /** String-resource key explaining why dictation ended without a result. */
    data class Unavailable(val messageKey: String) : DictationState
}

/**
 * Live microphone dictation for *typing* caption text hands-free, built on the platform
 * [SpeechRecognizer]. This is deliberately separate from file transcription:
 * [SpeechRecognizer] consumes the mic, not URIs, so it powers "speak the line while previewing"
 * editing rather than auto-captions.
 *
 * Graceful degradation: when no recognition service is present, or recognition fails offline,
 * the flow emits [DictationState.Unavailable] with a localized key instead of throwing — the UI
 * simply hides/disables the mic affordance.
 */
@Singleton
class DictationTranscriber @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun isAvailable(): Boolean = runCatching {
        SpeechRecognizer.isRecognitionAvailable(context)
    }.getOrDefault(false)

    /**
     * Starts one dictation session. Requires RECORD_AUDIO granted by the caller. The returned
     * flow completes after [DictationState.Final] or [DictationState.Unavailable]; cancelling
     * collection stops the recognizer.
     */
    fun dictation(languageTag: String): Flow<DictationState> = callbackFlow {
        if (!isAvailable()) {
            trySend(DictationState.Unavailable("captions_dictation_unavailable"))
            close()
            return@callbackFlow
        }
        val recognizer = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }
            .getOrElse {
                trySend(DictationState.Unavailable("captions_dictation_unavailable"))
                close()
                return@callbackFlow
            }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend(DictationState.Listening)
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                val key = when (error) {
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER -> "captions_dictation_offline"

                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "captions_dictation_no_match"

                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "error_permission_denied"
                    else -> "captions_dictation_unavailable"
                }
                VitaLog.i("Dictation", "Recognizer error $error → $key")
                trySend(DictationState.Unavailable(key))
                close()
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isBlank()) {
                    trySend(DictationState.Unavailable("captions_dictation_no_match"))
                } else {
                    trySend(DictationState.Final(text))
                }
                close()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isNotBlank()) trySend(DictationState.Partial(text))
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer.startListening(intent)

        awaitClose {
            runCatching { recognizer.stopListening() }
            runCatching { recognizer.destroy() }
        }
    }
}
