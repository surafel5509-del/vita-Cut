package com.vitacut.core.media.thumbnails

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import com.vitacut.core.common.logging.VitaLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cached frame/thumbnail extraction.
 *
 * Two caches back the timeline & browser:
 * - [thumbnailCache] — one small bitmap per media URI (project cards, browser grid).
 * - [stripCache] — the filmstrip frames of a clip at a given pixels-per-second scale bucket.
 *
 * Bitmaps are decoded at the *requested* size (inSampleSize) — full-resolution frames are never
 * held in memory, which is the main OOM defense for the editor.
 */
@Singleton
class ThumbnailProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 6 // ~16% of heap for media thumbnails

    private val thumbnailCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val stripCache = object : LruCache<String, List<Bitmap>>(cacheSize) {
        override fun sizeOf(key: String, value: List<Bitmap>): Int =
            value.sumOf { it.byteCount } / 1024
    }

    /** Small representative thumbnail (e.g. 160x90 class) for cards & grids. */
    suspend fun thumbnail(uri: String, widthPx: Int = 160, heightPx: Int = 160): Bitmap? =
        withContext(Dispatchers.IO) {
            val key = "thumb|$uri|$widthPx"
            thumbnailCache.get(key)?.let { return@withContext it }
            val bitmap = decodeFrame(uri, timeUs = 0L, targetWidth = widthPx, targetHeight = heightPx)
            bitmap?.let { thumbnailCache.put(key, it) }
            bitmap
        }

    /** A specific frame — used by the preview poster & freeze-frame picking. */
    suspend fun frameAt(uri: String, timeUs: Long, targetWidth: Int = 480): Bitmap? =
        withContext(Dispatchers.IO) {
            val key = "frame|$uri|$timeUs|$targetWidth"
            thumbnailCache.get(key)?.let { return@withContext it }
            val bitmap = decodeFrame(uri, timeUs, targetWidth, targetWidth * 2)
            bitmap?.let { thumbnailCache.put(key, it) }
            bitmap
        }

    /**
     * Filmstrip frames covering [startUs]..[endUs] for a timeline clip rendered [targetCount]
     * cells wide. Results are cached by a key that includes the zoom bucket so pinch-zooming the
     * timeline re-renders strips only when the sampling density actually changes.
     */
    suspend fun strip(
        uri: String,
        startUs: Long,
        endUs: Long,
        targetCount: Int,
        cellWidthPx: Int,
        cellHeightPx: Int,
    ): List<Bitmap> = withContext(Dispatchers.IO) {
        val bucket = zoomBucket(cellWidthPx)
        val key = "strip|$uri|$startUs|$endUs|$targetCount|$bucket"
        stripCache.get(key)?.let { return@withContext it }

        val count = targetCount.coerceIn(1, 64)
        val span = (endUs - startUs).coerceAtLeast(1L)
        val frames = (0 until count).mapNotNull { index ->
            val timeUs = startUs + span * index / count
            decodeFrame(uri, timeUs, cellWidthPx, cellHeightPx)
        }
        if (frames.isNotEmpty()) stripCache.put(key, frames)
        frames
    }

    private fun zoomBucket(cellWidthPx: Int): Int = when {
        cellWidthPx <= 24 -> 0
        cellWidthPx <= 48 -> 1
        cellWidthPx <= 96 -> 2
        else -> 3
    }

    /**
     * Decodes one frame scaled to fit [targetWidth]x[targetHeight].
     *
     * Uses [MediaMetadataRetriever] for video and [BitmapFactory] with inSampleSize for images so
     * we never allocate a full-resolution bitmap just to shrink it afterwards.
     */
    private fun decodeFrame(uri: String, timeUs: Long, targetWidth: Int, targetHeight: Int): Bitmap? {
        return try {
            val parsed = Uri.parse(uri)
            val mime = context.contentResolver.getType(parsed) ?: ""
            if (mime.startsWith("image/") && !mime.equals("image/gif", true)) {
                decodeImage(parsed, targetWidth, targetHeight)
            } else {
                decodeVideoFrame(parsed, timeUs, targetWidth, targetHeight)
            }
        } catch (oom: OutOfMemoryError) {
            VitaLog.w("ThumbnailProvider", "OOM decoding frame of $uri @${timeUs}us")
            trimCaches()
            null
        } catch (t: Throwable) {
            VitaLog.d("ThumbnailProvider", "Failed to decode frame of $uri: ${t.message}")
            null
        }
    }

    private fun decodeVideoFrame(uri: Uri, timeUs: Long, targetWidth: Int, targetHeight: Int): Bitmap? {
        // A fresh retriever per call: instances are not thread-safe, so no sharing/locking needed.
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val frame = retriever.getFrameAtTime(
                timeUs.coerceAtLeast(0L),
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            ) ?: return null
            scaleDown(frame, targetWidth, targetHeight)
        } catch (t: Throwable) {
            // OPTION_CLOSEST_SYNC can fail on sparse keyframes; retry once with CLOSEST.
            runCatching {
                retriever.setDataSource(context, uri)
                val frame = retriever.getFrameAtTime(timeUs.coerceAtLeast(0L), MediaMetadataRetriever.OPTION_CLOSEST)
                frame?.let { scaleDown(it, targetWidth, targetHeight) }
            }.getOrNull()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun decodeImage(uri: Uri, targetWidth: Int, targetHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth && bounds.outHeight / (sample * 2) >= targetHeight) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null
        return scaleDown(decoded, targetWidth, targetHeight)
    }

    private fun scaleDown(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        if (source.width <= targetWidth && source.height <= targetHeight) return source
        val scale = minOf(targetWidth.toFloat() / source.width, targetHeight.toFloat() / source.height)
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, w, h, true)
        if (scaled != source) source.recycle()
        return scaled
    }

    private fun trimCaches() {
        thumbnailCache.trimToSize(thumbnailCache.maxSize() / 2)
        stripCache.trimToSize(stripCache.maxSize() / 2)
    }

    /** Total cache pressure in KB — surfaced in Settings → Cache management. */
    fun cacheSizeKb(): Int = thumbnailCache.size() + stripCache.size()

    fun clearCaches() {
        thumbnailCache.evictAll()
        stripCache.evictAll()
    }
}
