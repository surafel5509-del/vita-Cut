package com.vitacut.core.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.vitacut.core.common.logging.VitaLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Moves a finished export file into user-visible storage:
 * - Default: MediaStore (Movies/VitaCut) — no permission needed on API 29+; on API 26–28 uses
 *   the legacy IS_PENDING-free path with WRITE_EXTERNAL_STORAGE (declared with maxSdkVersion 28).
 * - Optional: a user-picked SAF folder (tree Uri) when the user chose "Export location".
 */
@Singleton
class ExportPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    data class PublishResult(val uri: Uri, val displayName: String)

    suspend fun publishToMediaStore(file: File, displayName: String, relativePath: String? = null): PublishResult =
        withContext(Dispatchers.IO) {
            val name = displayName.ensureMp4()
            val resolver = context.contentResolver
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.IS_PENDING, 1)
                val dir = relativePath
                    ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        "${android.os.Environment.DIRECTORY_MOVIES}/VitaCut"
                    } else null
                if (dir != null) put(MediaStore.Video.Media.RELATIVE_PATH, dir)
            }
            val uri = resolver.insert(collection, values)
                ?: throw IllegalStateException("MediaStore insert returned null")
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("Cannot open output stream")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            runCatching { file.delete() }
            PublishResult(uri, name)
        }

    /** Publish into a SAF tree Uri previously granted by the user. */
    suspend fun publishToTree(treeUri: Uri, file: File, displayName: String): PublishResult =
        withContext(Dispatchers.IO) {
            val tree = DocumentFile.fromTreeUri(context, treeUri)
                ?: throw IllegalStateException("Invalid tree uri")
            val name = displayName.ensureMp4()
            // Replace a same-name file rather than letting DocumentFile create "name (1).mp4".
            tree.findFile(name)?.delete()
            val doc = tree.createFile("video/mp4", name)
                ?: throw IllegalStateException("Cannot create file in target folder")
            context.contentResolver.openOutputStream(doc.uri, "wt")?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("Cannot open tree output stream")
            runCatching { file.delete() }
            PublishResult(doc.uri, name)
        }

    /** Share intent for the exported video (FileProvider when sharing a raw file). */
    fun shareIntent(fileOrUri: Any, displayName: String): Intent {
        val uri = when (fileOrUri) {
            is File -> FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                fileOrUri,
            )

            is Uri -> fileOrUri
            else -> throw IllegalArgumentException("Unsupported share payload")
        }
        return Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, displayName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun String.ensureMp4(): String = if (endsWith(".mp4", ignoreCase = true)) this else "$this.mp4"
}
