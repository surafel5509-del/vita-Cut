package com.vitacut.core.media.proxy

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Scale
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.vitacut.core.common.logging.VitaLog
import com.vitacut.core.common.result.VitaError
import com.vitacut.core.common.result.VitaResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Generates low-resolution H.264 proxy files for large sources (PERFORMANCE mode).
 *
 * The proxy keeps the original's aspect & duration; the editor swaps it in for preview decoding
 * only — export always uses the original unless the user explicitly enables proxy export.
 * Built on the same Media3 Transformer used by the export engine, so proxies are hardware
 * accelerated wherever the platform supports it.
 */
@Singleton
class ProxyGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * @param maxDimension longest edge of the proxy, typically 720 or 540.
     */
    suspend fun generate(
        sourceUri: Uri,
        sourceWidth: Int,
        sourceHeight: Int,
        maxDimension: Int = 720,
    ): VitaResult<File> = withContext(Dispatchers.IO) {
        val longest = maxOf(sourceWidth, sourceHeight)
        if (longest <= maxDimension) {
            return@withContext VitaResult.Failure(VitaError.UnsupportedMedia("source already small"))
        }
        val scale = maxDimension.toFloat() / longest
        val outputFile = File(context.cacheDir, "proxies/proxy_${System.currentTimeMillis()}.mp4")
        outputFile.parentFile?.mkdirs()

        suspendCancellableCoroutine { continuation ->
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_MIME_TYPE_VIDEO_H264)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        if (continuation.isActive) continuation.resume(VitaResult.Success(outputFile))
                    }

                    override fun onError(
                        composition: Composition,
                        result: ExportResult,
                        exception: ExportException,
                    ) {
                        VitaLog.w("ProxyGenerator", "Proxy failed: ${exception.message}")
                        outputFile.delete()
                        if (continuation.isActive) {
                            continuation.resume(
                                VitaResult.Failure(VitaError.ExportFailed(exception.message ?: "proxy", exception.errorCode)),
                            )
                        }
                    }
                })
                .build()

            val mediaItem = MediaItem.Builder().setUri(sourceUri).build()
            val edited = EditedMediaItem.Builder(mediaItem)
                .setEffects(
                    androidx.media3.transformer.Effects(
                        /* audioProcessors= */ emptyList(),
                        // Scale keeps aspect ratio; Transformer writes the scaled size out.
                        /* videoEffects= */ listOf(Scale(scale, scale)),
                    ),
                )
                .build()
            val composition = Composition.Builder(EditedMediaItemSequence(edited)).build()

            continuation.invokeOnCancellation {
                runCatching { transformer.cancel() }
                outputFile.delete()
            }
            transformer.start(composition, outputFile.absolutePath)
        }
    }
}
