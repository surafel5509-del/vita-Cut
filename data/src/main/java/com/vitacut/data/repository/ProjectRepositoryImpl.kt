package com.vitacut.data.repository

import com.vitacut.core.common.logging.VitaLog
import com.vitacut.core.database.dao.ProjectDao
import com.vitacut.core.database.entity.ProjectEntity
import com.vitacut.core.database.entity.ProjectStatus
import com.vitacut.core.model.CanvasSettings
import com.vitacut.core.model.Project
import com.vitacut.core.model.ProjectId
import com.vitacut.core.model.projectFromJson
import com.vitacut.core.model.toJson
import com.vitacut.core.timeline.ProjectDocument
import com.vitacut.domain.repository.ProjectRepository
import com.vitacut.domain.repository.ProjectSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed project persistence.
 *
 * Robustness rules honored here:
 * - A corrupt committed JSON falls back to the pending snapshot (and vice versa) before giving
 *   up; a fully unreadable project yields null so the UI shows "could not open" (never a crash).
 * - Media lives only as URIs inside the JSON — the DB stores metadata + document text.
 */
@Singleton
class ProjectRepositoryImpl @Inject constructor(
    private val projectDao: ProjectDao,
) : ProjectRepository {

    override fun observeProjects(): Flow<List<ProjectSummary>> =
        projectDao.observeAll().map { entities -> entities.map { it.toSummary() } }
            .flowOn(Dispatchers.IO)

    override suspend fun getSummary(projectId: String): ProjectSummary? = withContext(Dispatchers.IO) {
        runCatching { projectDao.findById(projectId)?.toSummary() }.getOrNull()
    }

    override suspend fun loadDocument(projectId: String): ProjectDocument? = withContext(Dispatchers.IO) {
        val entity = runCatching { projectDao.findById(projectId) }.getOrNull() ?: return@withContext null
        val project = decodeBest(entity) ?: return@withContext null
        ProjectDocument(project)
    }

    override suspend fun createProject(name: String, canvas: CanvasSettings): ProjectDocument =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val project = Project(
                id = ProjectId(),
                name = name.trim().ifEmpty { "Untitled" },
                createdAtMs = now,
                updatedAtMs = now,
                canvas = canvas,
            )
            val document = ProjectDocument(project)
            projectDao.upsert(project.toEntity(document))
            document
        }

    override suspend fun savePending(document: ProjectDocument) {
        withContext(Dispatchers.IO) {
            val project = document.project
            runCatching {
                projectDao.savePending(project.id.value, project.toJson(), System.currentTimeMillis())
            }.onFailure { VitaLog.w(TAG, "Autosave failed: ${it.message}") }
        }
    }

    override suspend fun commit(document: ProjectDocument) {
        withContext(Dispatchers.IO) {
            val project = document.project
            val now = System.currentTimeMillis()
            runCatching {
                projectDao.commitPending(project.id.value, project.toJson(), now)
                // Keep list-row metadata in sync so the dashboard never re-decodes JSON.
                projectDao.findById(project.id.value)?.let { entity ->
                    projectDao.update(
                        entity.copy(
                            name = project.name,
                            durationUs = project.durationUs,
                            canvasAspect = project.canvas.aspect,
                            resolutionLabel = "${project.canvas.width}x${project.canvas.height}",
                            updatedAtMs = now,
                        ),
                    )
                }
                document.markSaved()
            }.onFailure { VitaLog.e(TAG, "Commit failed", it) }
        }
    }

    override suspend fun rename(projectId: String, name: String) {
        withContext(Dispatchers.IO) {
            runCatching { projectDao.rename(projectId, name.trim(), System.currentTimeMillis()) }
        }
    }

    override suspend fun delete(projectId: String) {
        withContext(Dispatchers.IO) {
            runCatching { projectDao.deleteById(projectId) }
        }
    }

    override suspend fun duplicate(projectId: String, newName: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val entity = projectDao.findById(projectId) ?: return@runCatching null
                val project = decodeBest(entity)?.copy(
                    id = ProjectId(),
                    name = newName.trim().ifEmpty { entity.name },
                    createdAtMs = System.currentTimeMillis(),
                    updatedAtMs = System.currentTimeMillis(),
                ) ?: return@runCatching null
                projectDao.upsert(project.toEntity(ProjectDocument(project)))
                project.id.value
            }.getOrNull()
        }

    override suspend fun findRecoverable(): ProjectSummary? = withContext(Dispatchers.IO) {
        runCatching { projectDao.findRecoverable()?.toSummary() }.getOrNull()
    }

    override suspend fun resolveRecovery(projectId: String, keepPendingEdits: Boolean): ProjectDocument? =
        withContext(Dispatchers.IO) {
            val entity = runCatching { projectDao.findById(projectId) }.getOrNull()
                ?: return@withContext null
            if (keepPendingEdits) {
                val pending = entity.pendingJson
                if (pending != null) {
                    runCatching {
                        projectDao.commitPending(projectId, pending, System.currentTimeMillis())
                    }
                }
            } else {
                runCatching { projectDao.updatePending(projectId, null, entity.updatedAtMs) }
            }
            loadDocument(projectId)
        }

    override suspend fun updateThumbnail(projectId: String, path: String?) {
        withContext(Dispatchers.IO) {
            runCatching { projectDao.updateThumbnail(projectId, path) }
        }
    }

    override suspend fun createFromTemplate(template: Project, name: String): ProjectDocument? =
        withContext(Dispatchers.IO) {
            runCatching {
                val document = ProjectDocument(template)
                projectDao.upsert(template.toEntity(document))
                document
            }.getOrNull()
        }

    /** Committed JSON first; pending snapshot as the recovery fallback. Null only when both fail. */
    private fun decodeBest(entity: ProjectEntity): Project? =
        projectFromJson(entity.projectJson)
            ?: entity.pendingJson?.let { projectFromJson(it) }
            ?: run {
                VitaLog.e(TAG, "Project ${entity.projectId} unreadable (both snapshots corrupt)")
                null
            }

    private fun ProjectEntity.toSummary() = ProjectSummary(
        projectId = projectId,
        name = name,
        durationUs = durationUs,
        canvasAspect = canvasAspect,
        resolutionLabel = resolutionLabel,
        thumbnailPath = thumbnailPath,
        status = status,
        updatedAtMs = updatedAtMs,
        exportCount = exportCount,
        hasPendingEdits = pendingJson != null,
    )

    private fun Project.toEntity(document: ProjectDocument) = ProjectEntity(
        projectId = id.value,
        name = name,
        projectJson = document.project.toJson(),
        pendingJson = null,
        thumbnailPath = null,
        durationUs = durationUs,
        resolutionLabel = "${canvas.width}x${canvas.height}",
        canvasAspect = canvas.aspect,
        status = ProjectStatus.DRAFT,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
    )

    private companion object {
        const val TAG = "ProjectRepository"
    }
}
