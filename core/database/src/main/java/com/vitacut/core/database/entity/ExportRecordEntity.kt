package com.vitacut.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Status of an export record. */
object ExportStatus {
    const val PENDING = "pending"
    const val RUNNING = "running"
    const val SUCCEEDED = "succeeded"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
}

/**
 * One row per export attempt. Successful exports point at a file in app-private cache which the
 * user can share/save to gallery; failed/cancelled rows drive the "cleanup incomplete exports"
 * resilience path on next launch.
 */
@Entity(
    tableName = "exports",
    indices = [Index("project_id"), Index("exported_at_ms")],
)
data class ExportRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "project_id")
    val projectId: String,

    val status: String = ExportStatus.PENDING,

    /** Absolute path of the (possibly temporary) output file. */
    @ColumnInfo(name = "file_path")
    val filePath: String? = null,

    /** URI after the file has been published to MediaStore (Downloads/Movies/VitaCut). */
    @ColumnInfo(name = "published_uri")
    val publishedUri: String? = null,

    @ColumnInfo(name = "resolution_label")
    val resolutionLabel: String = "",

    @ColumnInfo(name = "file_size_bytes")
    val fileSizeBytes: Long = 0L,

    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,

    @ColumnInfo(name = "exported_at_ms")
    val exportedAtMs: Long,
)
