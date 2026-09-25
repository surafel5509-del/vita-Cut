package com.vitacut.core.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.TaskStackBuilder
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vitacut.core.common.device.DeviceCapabilities
import com.vitacut.core.common.logging.VitaLog
import com.vitacut.core.database.dao.ExportRecordDao
import com.vitacut.core.database.dao.ProjectDao
import com.vitacut.core.database.entity.ExportStatus
import com.vitacut.core.database.entity.ProjectStatus
import com.vitacut.core.datastore.SettingsStore
import com.vitacut.core.model.ExportSettings
import com.vitacut.core.model.Project
import com.vitacut.core.model.VitaJson
import com.vitacut.core.model.projectFromJson
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.singleOrNull

/**
 * Background export (spec requirement): exports continue when the app is backgrounded via a
 * foreground-info worker with a live progress notification, and every attempt is recorded in the
 * DB so the app can report completion/failure even if the UI process died.
 *
 * Flow: load project (pending autosave JSON wins) → decode requested [ExportSettings] (falling
 * back to the stored defaults) → [ExportPlanner] validates/degrades against [DeviceCapabilities]
 * → [ExportEngine] streams progress → [ExportPublisher] moves the file into user-visible storage
 * (SAF tree when configured, else MediaStore) → export record updated.
 */
@HiltWorker
class ExportWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val projectDao: ProjectDao,
    private val exportRecordDao: ExportRecordDao,
    private val settingsStore: SettingsStore,
    private val exportEngine: ExportEngine,
    private val publisher: ExportPublisher,
    private val capabilities: DeviceCapabilities,
    private val resources: ExportResourceProvider,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val projectId = inputData.getString(KEY_PROJECT_ID)
            ?: return Result.failure(workDataOf(KEY_ERROR to "project_missing"))
        val exportRecordId = inputData.getLong(KEY_EXPORT_RECORD_ID, -1L)
        val treeUriInput = inputData.getString(KEY_TREE_URI)
        val settingsJson = inputData.getString(KEY_SETTINGS_JSON)

        createNotificationChannel()
        setForegroundSafely(foregroundInfo(0))

        val entity = runCatching { projectDao.findById(projectId) }.getOrNull()
        val project: Project? = entity?.let { projectFromJson(it.pendingJson ?: it.projectJson) }
        if (project == null) {
            fail(exportRecordId, ExportStatus.FAILED, "project_missing")
            return Result.failure(workDataOf(KEY_ERROR to "project_missing"))
        }

        val appSettings = settingsStore.settings.first()
        val settings = decodeSettings(settingsJson) ?: appSettings.defaultExportSettings
        val treeUri = treeUriInput?.let(Uri::parse)
            ?: appSettings.exportLocationUri?.takeIf { it.isNotBlank() }?.let(Uri::parse)

        val plan = when (val validation = ExportPlanner.plan(settings, project, capabilities.snapshot())) {
            is ExportValidation.Invalid -> {
                fail(exportRecordId, ExportStatus.FAILED, validation.messageKey)
                return Result.failure(workDataOf(KEY_ERROR to validation.messageKey))
            }

            is ExportValidation.Ready -> validation.plan
        }
        if (plan.fallbacks.isNotEmpty()) {
            VitaLog.i("ExportWorker", "Applied fallbacks: ${plan.fallbacks.map { it.messageKey }}")
        }

        if (exportRecordId >= 0) {
            runCatching { exportRecordDao.updateStatus(exportRecordId, ExportStatus.RUNNING) }
        }

        val request = ExportRequest(
            project = project,
            plan = plan,
            outputName = project.name,
            useProxies = settings.useProxies,
        )

        val terminal = exportEngine
            .export(request, resources.imageFrameSource(), resources.lutResolver())
            .onEach { state ->
                if (state is ExportState.Running) setForegroundSafely(foregroundInfo(state.percent))
            }
            .singleOrNull { it is ExportState.Succeeded || it is ExportState.Failed || it is ExportState.Cancelled }

        return when (terminal) {
            is ExportState.Succeeded -> {
                val published = runCatching {
                    if (treeUri != null) {
                        publisher.publishToTree(treeUri, terminal.file, project.name)
                    } else {
                        publisher.publishToMediaStore(terminal.file, project.name)
                    }
                }.getOrElse {
                    VitaLog.e("ExportWorker", "Publish failed", it)
                    fail(exportRecordId, ExportStatus.FAILED, "publish_failed: ${it.message}")
                    return Result.failure(workDataOf(KEY_ERROR to "publish_failed"))
                }
                val sizeBytes = runCatching {
                    applicationContext.contentResolver.openFileDescriptor(published.uri, "r")
                        ?.use { it.statSize }
                }.getOrNull() ?: 0L
                if (exportRecordId >= 0) {
                    runCatching {
                        exportRecordDao.markSucceeded(
                            exportRecordId,
                            ExportStatus.SUCCEEDED,
                            filePath = null,
                            publishedUri = published.uri.toString(),
                            sizeBytes = sizeBytes,
                        )
                    }
                }
                runCatching {
                    projectDao.incrementExportCount(projectId)
                    projectDao.updateStatus(projectId, ProjectStatus.EXPORTED)
                }
                Result.success(
                    workDataOf(
                        KEY_OUTPUT_URI to published.uri.toString(),
                        KEY_OUTPUT_NAME to published.displayName,
                    ),
                )
            }

            is ExportState.Failed -> {
                fail(exportRecordId, ExportStatus.FAILED, "${terminal.messageKey}: ${terminal.detail}")
                Result.failure(workDataOf(KEY_ERROR to terminal.messageKey))
            }

            else -> {
                fail(exportRecordId, ExportStatus.CANCELLED, "cancelled")
                Result.failure(workDataOf(KEY_ERROR to "cancelled"))
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0)

    private suspend fun fail(recordId: Long, status: String, detail: String) {
        if (recordId >= 0) {
            runCatching { exportRecordDao.updateStatus(recordId, status, detail) }
        }
    }

    private fun decodeSettings(json: String?): ExportSettings? = json?.let {
        runCatching { VitaJson.decodeFromString(ExportSettings.serializer(), it) }.getOrNull()
    }

    private fun setForegroundSafely(info: ForegroundInfo) {
        runCatching { setForeground(info) }
    }

    private fun foregroundInfo(percent: Int): ForegroundInfo {
        val notification = buildNotification(percent)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(percent: Int): Notification {
        val launch = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName) ?: Intent()
        val contentIntent = TaskStackBuilder.create(applicationContext)
            .addNextIntentWithParentStack(launch)
            .getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.export_notification_title))
            .setContentText(applicationContext.getString(R.string.export_progress_notification, percent))
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setProgress(100, percent, percent == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        applicationContext.getString(R.string.export_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }
        }
    }

    companion object {
        const val KEY_PROJECT_ID = "project_id"
        const val KEY_EXPORT_RECORD_ID = "export_record_id"
        const val KEY_SETTINGS_JSON = "settings_json"
        const val KEY_TREE_URI = "tree_uri"
        const val KEY_OUTPUT_URI = "output_uri"
        const val KEY_OUTPUT_NAME = "output_name"
        const val KEY_ERROR = "error"
        const val TAG = "vitacut_export_work"

        private const val CHANNEL_ID = "vitacut_export"
        private const val NOTIFICATION_ID = 4201

        /**
         * Enqueues the export. Callers must persist pending edits (autosave) *before* enqueueing
         * so the worker always reads a consistent snapshot from the DB.
         */
        fun enqueue(
            context: Context,
            projectId: String,
            exportRecordId: Long,
            settingsJson: String?,
            treeUri: String?,
        ) {
            val request = OneTimeWorkRequestBuilder<ExportWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(KEY_PROJECT_ID, projectId)
                        .putLong(KEY_EXPORT_RECORD_ID, exportRecordId)
                        .putString(KEY_SETTINGS_JSON, settingsJson)
                        .putString(KEY_TREE_URI, treeUri)
                        .build(),
                )
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }

        /** Cancels any running export (the UI's Cancel button). */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(TAG)
        }
    }
}
