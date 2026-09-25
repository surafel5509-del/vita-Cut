package com.vitacut.core.media.metadata

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.vitacut.core.common.logging.VitaLog
import com.vitacut.core.model.AssetId
import com.vitacut.core.model.MediaAsset
import com.vitacut.core.model.MediaKind
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads metadata for picked URIs and turns them into [MediaAsset] values.
 *
 * Unsupported or corrupt files never throw to callers: they come back with
 * `codecSupported = false` (or null) so the import UI can explain what happened.
 */
@Singleton
class MediaMetadataReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val supportedDecodeMimes: Set<String> by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder }
                .flatMap { info -> info.supportedTypes.asIterable() }
                .map { it.lowercase() }
                .toSet()
        }.getOrDefault(emptySet())
    }

    /** Classifies a MIME type into a [MediaKind], or null when the app cannot use the file. */
    fun kindForMime(mime: String?): MediaKind? = when {
        mime == null -> null
        mime.startsWith("video/") -> if (mime.equals("video/gif", true)) MediaKind.GIF else MediaKind.VIDEO
        mime.startsWith("image/gif") -> MediaKind.GIF
        mime.startsWith("image/") -> MediaKind.IMAGE
        mime.startsWith("audio/") -> MediaKind.AUDIO
        else -> null
    }

    /** Whether the device advertises a decoder for the primary track of [mime]. */
    fun isCodecSupported(mime: String?): Boolean {
        if (mime == null) return false
        // Container MIME (e.g. video/mp4) is decodable when at least one video decoder exists;
        // exact codec checks happen per-track below in readAsset.
        return supportedDecodeMimes.any { it.startsWith(mime.substringBefore("/")) }
    }

    /**
     * Builds a [MediaAsset] for [uri]. Returns null only when the file cannot be opened at all
     * (deleted, revoked permission). Codec-level problems are reported on the asset instead.
     */
    fun readAsset(uri: Uri): MediaAsset? {
        val mime = context.contentResolver.getType(uri)
        val displayName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "media"
        val kind = kindForMime(mime) ?: guessKindFromName(displayName) ?: return null

        var durationUs = 0L
        var width = 0
        var height = 0
        var rotation = 0
        var frameRate = 0f
        var codecSupported = true

        if (kind == MediaKind.VIDEO || kind == MediaKind.AUDIO || kind == MediaKind.GIF) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                durationUs = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L) * 1000L
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
                rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
                frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toFloatOrNull() ?: 0f
                // If the retriever produced neither duration nor dimensions for A/V media the
                // file is likely corrupt or its codec is unsupported on this device.
                if (durationUs == 0L && width == 0) {
                    codecSupported = false
                }
            } catch (t: Throwable) {
                VitaLog.w("MediaMetadataReader", "Cannot read $uri: ${t.message}")
                codecSupported = false
            } finally {
                runCatching { retriever.release() }
            }
        }

        if (kind == MediaKind.IMAGE || kind == MediaKind.GIF) {
            decodeImageBounds(uri)?.let { (w, h) ->
                width = w
                height = h
            }
        }

        val sizeBytes = querySize(uri)

        return MediaAsset(
            id = AssetId(),
            uri = uri.toString(),
            kind = kind,
            mimeType = mime ?: "",
            displayName = displayName,
            durationUs = durationUs,
            width = width,
            height = height,
            rotationDegrees = rotation,
            frameRate = frameRate,
            sizeBytes = sizeBytes,
            dateAddedMs = System.currentTimeMillis(),
            codecSupported = codecSupported,
        )
    }

    /** Reads several URIs; failures are reported as nulls, never exceptions. */
    fun readAssets(uris: List<Uri>): List<MediaAsset?> = uris.map { runCatching { readAsset(it) }.getOrNull() }

    private fun decodeImageBounds(uri: Uri): Pair<Int, Int>? = runCatching {
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            android.graphics.BitmapFactory.decodeStream(input, null, options)
        }
        if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    }.getOrNull()

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else null
            }
    }.getOrNull()

    private fun querySize(uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else 0L
                } else 0L
            } ?: 0L
    }.getOrDefault(0L)

    private fun guessKindFromName(name: String): MediaKind? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4", "mov", "mkv", "webm", "avi", "3gp", "m4v" -> MediaKind.VIDEO
            "jpg", "jpeg", "png", "webp", "bmp", "heic" -> MediaKind.IMAGE
            "gif" -> MediaKind.GIF
            "mp3", "wav", "aac", "m4a", "ogg", "flac", "amr" -> MediaKind.AUDIO
            else -> null
        }
    }

    /** Checks whether a specific track format (e.g. inside MKV) can be decoded here. */
    fun canDecodeFormat(format: MediaFormat): Boolean {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
        return mime.lowercase() in supportedDecodeMimes
    }
}
