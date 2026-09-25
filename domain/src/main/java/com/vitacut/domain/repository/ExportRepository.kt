package com.vitacut.domain.repository

import com.vitacut.core.model.ExportSettings
import kotlinx.coroutines.flow.Flow

/** Domain view of one export attempt (mirrors the DB record without Room types). */
data class ExportRecord(
    val id: Long,
    val projectId: String,
    val status: ExportJobStatus,
    val publishedUri: String?,
    val resolutionLabel: String,
    val fileSizeBytes: Long,
    val errorMessage: String?,
    val exportedAtMs: Long,
)

enum class ExportJobStatus { PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED }

/**
 * Export orchestration boundary. The implementation (in :data) owns WorkManager scheduling,
 * DB bookkeeping and the MediaStore/SAF publish step.
 */
interface ExportRepository {

    /**
     * Persists any pending edits, creates the export record and enqueues the background job.
     * @return the export record id (usable to observe/cancel this attempt).
     */
    suspend fun startExport(
        projectId: String,
        settings: ExportSettings,
        destinationTreeUri: String? = null,
    ): Long

    /** Live WorkManager-driven progress (0..100) for the running job, if any. */
    fun observeProgress(): Flow<Int?>

    fun observeRecords(projectId: String? = null): Flow<List<ExportRecord>>

    fun cancelExport()
}
