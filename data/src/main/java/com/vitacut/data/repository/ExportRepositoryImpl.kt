package com.vitacut.data.repository

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.vitacut.core.database.dao.ExportRecordDao
import com.vitacut.core.database.entity.ExportRecordEntity
import com.vitacut.core.database.entity.ExportStatus
import com.vitacut.core.export.ExportWorker
import com.vitacut.core.model.ExportSettings
import com.vitacut.core.model.VitaJson
import com.vitacut.domain.repository.ExportJobStatus
import com.vitacut.domain.repository.ExportRecord
import com.vitacut.domain.repository.ExportRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager-backed export orchestration: enqueue is fire-and-forget (the OS keeps the job
 * alive across process death), progress is observed from the worker's reported Data, and every
 * attempt is mirrored into the exports table for the history UI.
 */
@Singleton
class ExportRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val exportRecordDao: ExportRecordDao,
) : ExportRepository {

    override suspend fun startExport(
        projectId: String,
        settings: ExportSettings,
        destinationTreeUri: String?,
    ): Long = withContext(Dispatchers.IO) {
        val recordId = exportRecordDao.insert(
            ExportRecordEntity(
                projectId = projectId,
                status = ExportStatus.PENDING,
                resolutionLabel = settings.resolution.label,
                exportedAtMs = System.currentTimeMillis(),
            ),
        )
        val settingsJson = runCatching {
            VitaJson.encodeToString(ExportSettings.serializer(), settings)
        }.getOrNull()
        ExportWorker.enqueue(
            context = context,
            projectId = projectId,
            exportRecordId = recordId,
            settingsJson = settingsJson,
            treeUri = destinationTreeUri,
        )
        recordId
    }

    override fun observeProgress(): Flow<Int?> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(ExportWorker.TAG)
            .map { infos ->
                val active = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                active?.progress?.getInt(ExportWorker.KEY_PROGRESS, 0)
            }
            .flowOn(Dispatchers.Default)

    override fun observeRecords(projectId: String?): Flow<List<ExportRecord>> {
        val source = if (projectId != null) {
            exportRecordDao.observeForProject(projectId)
        } else {
            exportRecordDao.observeAll()
        }
        return source.map { entities -> entities.map { it.toDomain() } }.flowOn(Dispatchers.IO)
    }

    override fun cancelExport() {
        ExportWorker.cancel(context)
    }

    private fun ExportRecordEntity.toDomain() = ExportRecord(
        id = id,
        projectId = projectId,
        status = when (status) {
            ExportStatus.RUNNING -> ExportJobStatus.RUNNING
            ExportStatus.SUCCEEDED -> ExportJobStatus.SUCCEEDED
            ExportStatus.FAILED -> ExportJobStatus.FAILED
            ExportStatus.CANCELLED -> ExportJobStatus.CANCELLED
            else -> ExportJobStatus.PENDING
        },
        publishedUri = publishedUri,
        resolutionLabel = resolutionLabel,
        fileSizeBytes = fileSizeBytes,
        errorMessage = errorMessage,
        exportedAtMs = exportedAtMs,
    )
}
