package com.vitacut.core.captions

import android.net.Uri
import com.vitacut.core.common.logging.VitaLog
import com.vitacut.core.common.result.VitaError
import com.vitacut.core.common.result.VitaResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chooses among all bound [TranscriptionProvider]s: engines are tried in [TranscriptionProvider.priority]
 * order, skipping unavailable ones; the first success wins. Because [AssistedCaptionProvider]
 * is always available and last, [transcribe] always terminates with a usable result unless the
 * source file itself is broken.
 */
@Singleton
class TranscriptionRegistry @Inject constructor(
    providers: Set<@JvmSuppressWildcards TranscriptionProvider>,
) {

    private val ordered: List<TranscriptionProvider> = providers.sortedBy { it.priority }

    fun engines(): List<TranscriptionProvider> = ordered

    suspend fun availableEngines(): List<TranscriptionProvider> = ordered.filter {
        runCatching { it.isAvailable() }.getOrDefault(false)
    }

    suspend fun transcribe(
        uri: Uri,
        languageTag: String,
        onProgress: (Float) -> Unit = {},
    ): VitaResult<Transcript> {
        var lastError: VitaError = VitaError.CapabilityUnavailable("captions_engine_unavailable")
        for (provider in ordered) {
            val available = runCatching { provider.isAvailable() }.getOrDefault(false)
            if (!available) continue
            when (val result = runCatching { provider.transcribeAudio(uri, languageTag, onProgress) }
                .getOrElse { VitaResult.Failure(VitaError.Unknown(it)) }) {
                is VitaResult.Success -> return result
                is VitaResult.Failure -> {
                    VitaLog.i("Transcription", "${provider.id} failed: ${result.error.messageKey}")
                    lastError = result.error
                    // Corrupt/absent media will fail on every engine — stop early.
                    if (result.error is VitaError.UnsupportedMedia || result.error is VitaError.FileMissing) {
                        return result
                    }
                }
            }
        }
        return VitaResult.Failure(lastError)
    }
}
