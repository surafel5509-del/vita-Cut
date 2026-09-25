package com.vitacut.core.media.scan

import android.content.Context
import android.provider.MediaStore
import com.vitacut.core.model.MediaKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import javax.inject.Inject
import javax.inject.Singleton

/** A media file row surfaced by the in-app browser (MediaStore-backed). */
data class ScannedMedia(
    val id: Long,
    val uri: String,
    val displayName: String,
    val kind: MediaKind,
    val mimeType: String,
    val durationUs: Long,
    val sizeBytes: Long,
    val dateAddedMs: Long,
    val width: Int,
    val height: Int,
    val albumOrFolder: String,
)

/** Sort orders offered by the media browser. */
enum class MediaSortOrder { DATE_DESC, DATE_ASC, NAME_ASC, DURATION_DESC, SIZE_DESC }

/**
 * Browses device media through MediaStore.
 *
 * Only `READ_MEDIA_*` runtime permissions (or none at all when the Photo Picker is used) are
 * required — no broad `READ_EXTERNAL_STORAGE` on modern Android, per the scoped-storage rules.
 */
@Singleton
class MediaStoreScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Streams matching media. [query] filters by display name (case-insensitive);
     * [kinds] restricts the result set. Emission is chunked through a Flow so huge libraries
     * never block or OOM.
     */
    fun scan(
        kinds: Set<MediaKind>,
        query: String = "",
        sortOrder: MediaSortOrder = MediaSortOrder.DATE_DESC,
    ): Flow<List<ScannedMedia>> = flow {
        val results = mutableListOf<ScannedMedia>()
        if (kinds.any { it == MediaKind.VIDEO || it == MediaKind.GIF }) {
            results += queryCollection(
                collection = MediaStore.Files.getContentUri("external"),
                selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?",
                selectionArgs = arrayOf(MediaStore.MEDIA_TYPE_VIDEO.toString()),
                kind = MediaKind.VIDEO,
                query = query,
            )
        }
        if (MediaKind.IMAGE in kinds || MediaKind.GIF in kinds) {
            results += queryCollection(
                collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                selection = null,
                selectionArgs = null,
                kind = MediaKind.IMAGE,
                query = query,
            )
        }
        if (MediaKind.AUDIO in kinds) {
            results += queryCollection(
                collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                selection = null,
                selectionArgs = null,
                kind = MediaKind.AUDIO,
                query = query,
            )
        }

        val sorted = when (sortOrder) {
            MediaSortOrder.DATE_DESC -> results.sortedByDescending { it.dateAddedMs }
            MediaSortOrder.DATE_ASC -> results.sortedBy { it.dateAddedMs }
            MediaSortOrder.NAME_ASC -> results.sortedBy { it.displayName.lowercase() }
            MediaSortOrder.DURATION_DESC -> results.sortedByDescending { it.durationUs }
            MediaSortOrder.SIZE_DESC -> results.sortedByDescending { it.sizeBytes }
        }
        emit(sorted)
    }

    private suspend fun queryCollection(
        collection: android.net.Uri,
        selection: String?,
        selectionArgs: Array<String>?,
        kind: MediaKind,
        query: String,
    ): List<ScannedMedia> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )
        val effectiveSelection = if (query.isBlank()) selection else {
            listOfNotNull(
                selection,
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            ).joinToString(" AND ")
        }
        val effectiveArgs = if (query.isBlank()) selectionArgs else {
            (selectionArgs?.toList() ?: emptyList()) + "%$query%"
        }.toTypedArray()

        val out = mutableListOf<ScannedMedia>()
        val resolver = context.contentResolver
        resolver.query(
            collection,
            projection,
            effectiveSelection,
            effectiveArgs,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val durationCol = cursor.getColumnIndex(MediaStore.MediaColumns.DURATION)
            val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
            val widthCol = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
            val heightCol = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
            val pathCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)

            while (cursor.moveToNext()) {
                if (!currentCoroutineContext().isActive) return@use
                val id = cursor.getLong(idCol)
                val mime = cursor.getString(mimeCol) ?: ""
                val itemKind = when {
                    mime.equals("video/gif", true) || mime.equals("image/gif", true) -> MediaKind.GIF
                    else -> kind
                }
                out += ScannedMedia(
                    id = id,
                    uri = android.content.ContentUris.withAppendedId(collection, id).toString(),
                    displayName = cursor.getString(nameCol) ?: "",
                    kind = itemKind,
                    mimeType = mime,
                    durationUs = if (durationCol >= 0 && !cursor.isNull(durationCol)) {
                        cursor.getLong(durationCol) * 1000L
                    } else 0L,
                    sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L,
                    dateAddedMs = if (dateCol >= 0) cursor.getLong(dateCol) * 1000L else 0L,
                    width = if (widthCol >= 0) cursor.getInt(widthCol) else 0,
                    height = if (heightCol >= 0) cursor.getInt(heightCol) else 0,
                    albumOrFolder = if (pathCol >= 0) cursor.getString(pathCol) ?: "" else "",
                )
            }
        }
        return out
    }
}
