package com.vitacut.core.ai.tracking

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.vitacut.core.common.logging.VitaLog
import com.vitacut.core.common.result.VitaError
import com.vitacut.core.common.result.VitaResult
import com.vitacut.core.model.TrackPath
import com.vitacut.core.model.TrackPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** Region the user picked on the bind frame, in frame fractions (origin top-left). */
data class NormalizedRegion(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

/**
 * Basic motion tracking: samples decoded video frames and follows a user-picked region with the
 * NCC [TemplateMatcher], producing a [TrackPath] that the keyframe engine animates (mask,
 * sticker, or text bound to the path).
 *
 * Robustness choices:
 * - Fixed sample interval (default ~12 fps of analysis) — bounded work for any clip length.
 * - Search is windowed around the previous position; a low-score match *keeps* the previous
 *   center instead of teleporting (occlusion tolerance); the path is linearly interpolated by
 *   [TrackPath.sampleAt] between samples.
 * - Never throws: retrieval/decode failures return [VitaResult.Failure] with a localized key.
 */
@Singleton
class MotionTracker @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun track(
        uri: Uri,
        region: NormalizedRegion,
        startTimeUs: Long,
        endTimeUs: Long,
        intervalUs: Long = DEFAULT_INTERVAL_US,
        onProgress: (Float) -> Unit = {},
    ): VitaResult<TrackPath> = withContext(Dispatchers.Default) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri, emptyMap())
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: return@withContext VitaResult.Failure(VitaError.UnsupportedMedia("no-video-track"))
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: return@withContext VitaResult.Failure(VitaError.UnsupportedMedia("no-video-track"))
            if (width <= 0 || height <= 0) {
                return@withContext VitaResult.Failure(VitaError.UnsupportedMedia("bad-video-dimensions"))
            }

            val bindFrame = retriever.getFrameAtTime(startTimeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: return@withContext VitaResult.Failure(VitaError.CorruptMedia("no-bind-frame"))
            val bindGray = bitmapToGray(bindFrame, width, height)
            bindFrame.recycle()

            val tx = (region.x * width).toInt().coerceIn(0, width - 1)
            val ty = (region.y * height).toInt().coerceIn(0, height - 1)
            val tw = (region.width * width).toInt().coerceIn(8, width - tx)
            val th = (region.height * height).toInt().coerceIn(8, height - ty)
            val template = TemplateMatcher.crop(bindGray, width, tx, ty, tw, th)

            val radius = (maxOf(tw, th) * 1.5f).toInt().coerceIn(16, maxOf(width, height) / 3)
            val total = (endTimeUs - startTimeUs).coerceAtLeast(1L)
            val points = mutableListOf<TrackPoint>()
            var lastX = tx
            var lastY = ty
            var t = startTimeUs
            while (t <= endTimeUs && coroutineContext.isActive) {
                val frame = retriever.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST)
                if (frame != null) {
                    val gray = bitmapToGray(frame, width, height)
                    frame.recycle()
                    val match = TemplateMatcher.locate(
                        template, tw, th, gray, width, height,
                        searchCenterX = lastX, searchCenterY = lastY, searchRadius = radius,
                    )
                    if (match != null && match.score >= MIN_SCORE) {
                        lastX = match.x
                        lastY = match.y
                    }
                    // else: occlusion/blur → hold the previous position (smooth, no teleport).
                }
                points += TrackPoint(
                    timeUs = t,
                    x = ((lastX + tw / 2f) / width).coerceIn(0f, 1f),
                    y = ((lastY + th / 2f) / height).coerceIn(0f, 1f),
                    scale = 1f,
                )
                onProgress(((t - startTimeUs).toFloat() / total).coerceIn(0f, 1f))
                t += intervalUs
            }
            onProgress(1f)
            if (points.size < 2) {
                return@withContext VitaResult.Failure(VitaError.CapabilityUnavailable("tracking_no_motion"))
            }
            VitaResult.Success(TrackPath(points = points, regionSizeFraction = region.height))
        } catch (e: Exception) {
            VitaLog.w("MotionTracker", "Tracking failed: ${e.message}")
            VitaResult.Failure(VitaError.CorruptMedia(e.message ?: "tracking"))
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Re-encodes [source] to exactly [width]×[height] gray when the retriever hands back odd sizes. */
    private fun bitmapToGray(source: Bitmap, width: Int, height: Int): ByteArray {
        val scaled = if (source.width == width && source.height == height) {
            source
        } else {
            Bitmap.createScaledBitmap(source, width, height, true)
        }
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== source) scaled.recycle()
        return TemplateMatcher.toGray(pixels, width, height)
    }

    private companion object {
        const val DEFAULT_INTERVAL_US = 83_333L // ~12 analysis fps
        const val MIN_SCORE = 0.35f
    }
}
