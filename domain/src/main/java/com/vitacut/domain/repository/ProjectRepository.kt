package com.vitacut.domain.repository

import com.vitacut.core.model.CanvasSettings
import com.vitacut.core.model.Project
import com.vitacut.core.timeline.ProjectDocument
import kotlinx.coroutines.flow.Flow

/** Lightweight list-row data for the home dashboard (no full project JSON). */
data class ProjectSummary(
    val projectId: String,
    val name: String,
    val durationUs: Long,
    val canvasAspect: Float,
    val resolutionLabel: String,
    val thumbnailPath: String?,
    val status: String,
    val updatedAtMs: Long,
    val exportCount: Int,
    /** True when an autosaved-but-uncommitted edit snapshot exists (crash recovery hint). */
    val hasPendingEdits: Boolean = false,
)

/**
 * Project persistence boundary. Implemented in :data on top of Room + the JSON codec; the domain
 * and features only ever see [ProjectDocument]s and summaries.
 */
interface ProjectRepository {

    fun observeProjects(): Flow<List<ProjectSummary>>

    suspend fun getSummary(projectId: String): ProjectSummary?

    /** Loads (and decodes) a project into an editable document. Null when missing/corrupt. */
    suspend fun loadDocument(projectId: String): ProjectDocument?

    /** Creates and persists a fresh project, returning its document. */
    suspend fun createProject(name: String, canvas: CanvasSettings): ProjectDocument

    /** Autosave: stores the current JSON as the *pending* snapshot (crash recovery source). */
    suspend fun savePending(document: ProjectDocument)

    /** Explicit save: commits the pending snapshot as the project's canonical JSON. */
    suspend fun commit(document: ProjectDocument)

    suspend fun rename(projectId: String, name: String)

    suspend fun delete(projectId: String)

    /** Copies a project (assets are URI references, so duplication is metadata-only). */
    suspend fun duplicate(projectId: String, newName: String): String?

    /** Projects whose pending snapshot is newer than the committed JSON (crash recovery). */
    suspend fun findRecoverable(): ProjectSummary?

    /**
     * Resolves a crash-recovery choice: keep the pending snapshot (promote it to canonical) or
     * discard it (reload the committed JSON). Returns the resulting document.
     */
    suspend fun resolveRecovery(projectId: String, keepPending: Boolean): ProjectDocument?

    suspend fun updateThumbnail(projectId: String, path: String?)

    /** Instantiates a template into a new persisted project. */
    suspend fun createFromTemplate(template: Project, name: String): ProjectDocument?
}
