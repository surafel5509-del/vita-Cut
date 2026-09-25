package com.vitacut.core.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentationResult
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.vitacut.core.common.logging.VitaLog
import com.vitacut.core.common.result.VitaError
import com.vitacut.core.common.result.VitaResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Background removal via ML Kit subject segmentation.
 *
 * Produces an ARGB [Bitmap] whose alpha is the per-pixel foreground confidence — the result is
 * dropped onto an OVERLAY-track image item (or used to build a keyed sticker), so the existing
 * render pipeline composites it with zero special-casing.
 *
 * Degradation: the model is unbundled (needs Play services). When unavailable, callers get
 * [VitaResult.Failure] with `ai_requires_play_services` and the UI offers the manual
 * chroma-key / mask tools instead — the button is never dead, never crashes.
 */
@Singleton
class SubjectSegmenter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val capabilities: AiCapabilities,
) {

    private val segmenter: SubjectSegmenter? by lazy {
        if (!capabilities.isAvailable(AiFeature.SUBJECT_SEGMENTATION)) {
            null
        } else {
            runCatching {
                SubjectSegmentation.getClient(
                    SubjectSegmenterOptions.Builder()
                        .enableForegroundConfidenceMask()
                        .build(),
                )
            }.getOrElse {
                VitaLog.w(TAG, "Segmenter init failed: ${it.message}")
                null
            }
        }
    }

    fun isAvailable(): Boolean = segmenter != null

    /** Removes the background of a still image, returning a transparent-background ARGB copy. */
    suspend fun removeBackground(uri: Uri): VitaResult<Bitmap> {
        if (segmenter == null) {
            return VitaResult.Failure(VitaError.CapabilityUnavailable("ai_requires_play_services"))
        }
        // Decode with a size cap so huge photos don't OOM the segmenter.
        val source = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOptions(uri))
            }
        }.getOrNull()
            ?: return VitaResult.Failure(VitaError.CorruptMedia("undecodable-image"))
        return removeBackground(source)
    }

    private fun decodeOptions(uri: Uri): BitmapFactory.Options {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_EDGE || bounds.outHeight / (sample * 2) >= MAX_EDGE) {
            sample *= 2
        }
        return BitmapFactory.Options().apply { inSampleSize = sample }
    }

    /** Same, for an in-memory bitmap (video frame grabbed for "remove background on clip"). */
    suspend fun removeBackground(bitmap: Bitmap): VitaResult<Bitmap> {
        val client = segmenter
            ?: return VitaResult.Failure(VitaError.CapabilityUnavailable("ai_requires_play_services"))
        return runCatching {
            val result = client.process(InputImage.fromBitmap(bitmap, 0)).await()
            VitaResult.Success(applyMask(bitmap, result))
        }.getOrElse {
            VitaLog.w(TAG, "Segmentation failed: ${it.message}")
            VitaResult.Failure(VitaError.CapabilityUnavailable("ai_segmentation_failed"))
        }
    }

    /** Multiplies each pixel's alpha by the foreground confidence. */
    private fun applyMask(source: Bitmap, result: SubjectSegmentationResult): Bitmap {
        val mask = result.foregroundConfidenceMask
            ?: return source // no mask produced → keep original (graceful)
        val width = source.width
        val height = source.height
        val output = source.copy(Bitmap.Config.ARGB_8888, mutable = true)
        val pixels = IntArray(width * height)
        output.getPixels(pixels, 0, width, 0, 0, width, height)
        mask.rewind()
        for (i in 0 until width * height) {
            val confidence = if (mask.hasRemaining()) mask.float.coerceIn(0f, 1f) else 0f
            val pixel = pixels[i]
            val alpha = (Color.alpha(pixel) * confidence).toInt()
            pixels[i] = (alpha shl 24) or (pixel and 0x00FFFFFF)
        }
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    private companion object {
        const val TAG = "SubjectSegmenter"
        const val MAX_EDGE = 1600
    }
}
