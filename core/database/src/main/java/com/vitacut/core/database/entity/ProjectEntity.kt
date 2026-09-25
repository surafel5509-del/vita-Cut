package com.vitacut.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Lifecycle state of a project row. */
object ProjectStatus {
    const val DRAFT = "draft"
    const val ACTIVE = "active"
    const val EXPORTED = "exported"
    const val ARCHIVED = "archived"
}

/**
 * Persisted project record.
 *
 * The heavy payload — the complete editing document — lives in [projectJson] (see core:model
 * serialization). Everything else is denormalized metadata so the home screen list can render
 * without parsing JSON per row.
 *
 * [pendingJson] implements crash-safe autosave: before each significant mutation the editor
 * writes the new state here *first* (single transaction), then promotes it to [projectJson] when
 * the operation completes. If the process dies mid-operation, the recovery flow on next launch
 * offers to restore from [pendingJson] — see ProjectRepository.recoverableProject().
 */
@Entity(
    tableName = "projects",
    indices = [Index("updated_at_ms"), Index("status")],
)
data class ProjectEntity(
    @PrimaryKey
    @ColumnInfo(name = "project_id")
    val projectId: String,

    val name: String,

    @ColumnInfo(name = "project_json")
    val projectJson: String,

    /** In-flight autosave snapshot; null when the project is in a consistent state. */
    @ColumnInfo(name = "pending_json")
    val pendingJson: String? = null,

    /** Absolute path of a JPEG thumbnail inside app-private storage. */
    @ColumnInfo(name = "thumbnail_path")
    val thumbnailPath: String? = null,

    @ColumnInfo(name = "duration_us")
    val durationUs: Long = 0L,

    @ColumnInfo(name = "resolution_label")
    val resolutionLabel: String = "",

    @ColumnInfo(name = "canvas_aspect")
    val canvasAspect: Float = 16f / 9f,

    val status: String = ProjectStatus.DRAFT,

    /** Id of the template this project was created from (null = blank). */
    @ColumnInfo(name = "template_id")
    val templateId: String? = null,

    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,

    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,

    @ColumnInfo(name = "export_count")
    val exportCount: Int = 0,
)
