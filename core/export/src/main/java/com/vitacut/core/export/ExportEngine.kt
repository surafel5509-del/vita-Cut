package com.vitacut.core.export

import android.content.Context
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.VideoEncoderSettings
import com.vitacut.core.common.dispatcher.VitaDispatchers
import com.vitacut.core.common.dispatcher.Dispatcher
import com.vitacut.core.common.logging.VitaLog
import com.vitacut.core.model.ExportVideoCodec
import com.vitacut.core.model.Project
import com.vitacut.core.rendering.overlay.OverlayComposer
import com.vitacut.core.rendering.pipeline.EffectsPipelineBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** One export request, fully resolved (no IO needed to interpret it). */
data class ExportRequest(
    val project: Project,
    val plan: ExportPlan,
    val outputName: String,
    val useProxies: Boolean = false,
)

/** Progress/result states emitted while an export runs. */
sealed interface ExportState {
    data object Preparing : ExportState
    data class Running(
        val percent: Int,
        val bitrateKbps: Long,
        val elapsedMs: Long,
        val estimatedRemainingMs: Long?,
    ) : ExportState

    data class Succeeded(val file: File) : ExportState
    data class Failed(val messageKey: String, val detail: String, val errorCode: Int) : ExportState
    data object Cancelled : ExportState
}

/**
 * Runs exports on top of Media3's Transformer.
 *
 * Design notes:
 * - Transformer must live on a looper thread; the engine confines it to the main looper via
 *   [callbackFlow] collection semantics, while all file IO stays on the media dispatcher.
 * - Output is written to a `.part` temp file first and renamed only on success, so an
 *   interrupted export never leaves a file that looks complete (crash-resilience requirement).
 * - Cancellation calls [Transformer.cancel] and deletes the partial file.
 * - Encoder factory degrades gracefully: fallbacks enabled + explicit bitrate requests.
 */
@Singleton
class ExportEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val compositionFactory: CompositionFactory,
    private val storageGuard: StorageGuard,
    @Dispatcher(VitaDispatchers.Media) private val mediaDispatcher: kotlinx.coroutines.CoroutineDispatcher,
) {

    /**
     * Streams [ExportState]s for one export run. Cancelling the collection cancels the export.
     * The flow completes after emitting exactly one terminal state.
     */
    fun export(
        request: ExportRequest,
        imageFrameSource: OverlayComposer.ImageFrameSource?,
        lutResolver: EffectsPipelineBuilder.LutResolver?,
    ): Flow<ExportState> = callbackFlow {
        trySend(ExportState.Preparing)

        // Pre-flight checks on the IO dispatcher.
        val preflight = withContext(mediaDispatcher) {
            val estimated = ExportPlanner.estimatedOutputBytes(request.plan, request.project.durationUs)
            storageGuard.checkSpace(estimated * 2) // temp + final during rename
        }
        if (preflight != null) {
            trySend(preflight)
            close()
            return@callbackFlow
        }

        val tempFile = withContext(mediaDispatcher) {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            // Clean up any partial files from previously interrupted exports.
            dir.listFiles()?.filter { it.name.endsWith(".part") && it.lastModified() < System.currentTimeMillis() - STALE_PART_MS }
                ?.forEach { runCatching { it.delete() } }
            File(dir, "${sanitize(request.outputName)}.mp4.part")
        }

        val composition = try {
            compositionFactory.create(request.project, request.useProxies, imageFrameSource, lutResolver)
        } catch (t: Throwable) {
            VitaLog.e("ExportEngine", "Composition build failed", t)
            trySend(ExportState.Failed("error_export_failed", t.message ?: "composition", -1))
            close()
            return@callbackFlow
        }

        val startedAtMs = System.currentTimeMillis()
        val plan = request.plan

        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate(plan.videoBitrateBps)
                    .build(),
            )
            .setRequestedAudioEncoderSettings(
                AudioEncoderSettings.Builder()
                    .setBitrate(plan.audioBitrateBps)
                    .build(),
            )
            .setEnableFallback(true)
            .build()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(
                if (plan.settings.videoCodec == ExportVideoCodec.HEVC) {
                    MimeTypes.VIDEO_MIME_TYPE_VIDEO_H265
                } else {
                    MimeTypes.VIDEO_MIME_TYPE_VIDEO_H264
                },
            )
            .setAudioMimeType(MimeTypes.AUDIO_MIME_TYPE_AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onProgress(composition: Composition, progressPercent: Int, bitrate: Long) {
                    val elapsed = System.currentTimeMillis() - startedAtMs
                    val eta = if (progressPercent in 1..99) {
                        (elapsed * (100 - progressPercent) / progressPercent)
                    } else null
                    trySend(
                        ExportState.Running(
                            percent = progressPercent.coerceIn(0, 100),
                            bitrateKbps = bitrate / 1000,
                            elapsedMs = elapsed,
                            estimatedRemainingMs = eta,
                        ),
                    )
                }

                override fun onCompleted(composition: Composition, result: ExportResult) {
                    val finalFile = File(tempFile.absolutePath.removeSuffix(".part"))
                    val renamed = tempFile.renameTo(finalFile)
                    val output = if (renamed) finalFile else tempFile
                    if (renamed) {
                        // renameTo failed → keep the .part name but report success with the real path
                        VitaLog.w("ExportEngine", "Rename failed; keeping ${tempFile.name}")
                    }
                    trySend(ExportState.Succeeded(if (renamed) finalFile else output))
                    close()
                }

                override fun onError(
                    composition: Composition,
                    result: ExportResult,
                    exception: ExportException,
                ) {
                    VitaLog.e("ExportEngine", "Export failed: ${exception.message}", exception)
                    runCatching { tempFile.delete() }
                    val messageKey = when (exception.errorCode) {
                        ExportException.ERROR_CODE_NO_TRACKS -> "export_error_no_tracks"
                        ExportException.ERROR_CODE_UNSUPPORTED_TYPE -> "export_error_unsupported_codec"
                        ExportException.ERROR_CODE_DECODER_INIT_FAILED,
                        ExportException.ERROR_CODE_DECODER_QUERY_FAILED -> "export_error_decoder"

                        ExportException.ERROR_CODE_ENCODER_INIT_FAILED -> "export_error_encoder"
                        ExportException.ERROR_CODE_IO_FILE_NOT_FOUND -> "error_file_missing"
                        ExportException.ERROR_CODE_IO_NO_SPACE -> "error_insufficient_storage"
                        else -> "error_export_failed"
                    }
                    trySend(
                        ExportState.Failed(
                            messageKey = messageKey,
                            detail = exception.message ?: "unknown",
                            errorCode = exception.errorCode,
                        ),
                    )
                    close()
                }
            })
            .build()

        transformer.start(composition, tempFile.absolutePath)

        awaitClose {
            runCatching { transformer.cancel() }
            if (tempFile.exists()) runCatching { tempFile.delete() }
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.Main.immediate)

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9 ._\\-]"), "_").take(80).ifBlank { "vitacut_export" }

    companion object {
        private const val STALE_PART_MS = 12 * 60 * 60 * 1000L // 12h
    }
}

/**
 * Verifies there is enough free space before (and conceptually during) exports, translating the
 * failure into a localized, actionable error instead of a mid-encode IO crash.
 */
@Singleton
class StorageGuard @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Returns a terminal failure state when space is insufficient, null when OK. */
    fun checkSpace(requiredBytes: Long): ExportState? {
        val freeBytes = runCatching {
            val stat = android.os.StatFs(context.cacheDir.absolutePath)
            stat.availableBytes
        }.getOrDefault(Long.MAX_VALUE)
        return if (freeBytes < requiredBytes) {
            ExportState.Failed(
                messageKey = "error_insufficient_storage",
                detail = "need=$requiredBytes free=$freeBytes",
                errorCode = -2,
            )
        } else null
    }

    fun freeCacheBytes(): Long = runCatching {
        context.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    /** Deletes stale export artifacts (.part files and orphaned temp exports). */
    suspend fun cleanupIncompleteExports() = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports")
        dir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".part")) runCatching { file.delete() }
        }
        val proxies = File(context.cacheDir, "proxies")
        proxies.listFiles()
            ?.filter { it.lastModified() < System.currentTimeMillis() - 7L * 24 * 3600 * 1000 }
            ?.forEach { runCatching { it.delete() } }
    }
}
