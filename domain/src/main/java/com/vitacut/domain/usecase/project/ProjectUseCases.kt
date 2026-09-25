package com.vitacut.domain.usecase.project

import com.vitacut.core.model.AspectRatio
import com.vitacut.core.model.CanvasSettings
import com.vitacut.core.timeline.ProjectDocument
import com.vitacut.domain.repository.ProjectRepository
import com.vitacut.domain.repository.ProjectSummary
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** Creates a project with the chosen canvas preset and persists the initial snapshot. */
class CreateProjectUseCase @Inject constructor(
    private val repository: ProjectRepository,
) {
    suspend operator fun invoke(
        name: String,
        aspectRatio: AspectRatio = AspectRatio.RATIO_16_9,
    ): ProjectDocument = repository.createProject(
        name = name,
        canvas = CanvasSettings(aspectRatio = aspectRatio),
    )
}

class ObserveProjectsUseCase @Inject constructor(
    private val repository: ProjectRepository,
) {
    operator fun invoke(): Flow<List<ProjectSummary>> = repository.observeProjects()
}

/**
 * Opens a project. When a pending (autosaved, uncommitted) snapshot exists the caller must
 * first present the crash-recovery choice via [RecoverProjectUseCase]; otherwise this loads
 * the canonical JSON.
 */
class OpenProjectUseCase @Inject constructor(
    private val repository: ProjectRepository,
) {
    suspend operator fun invoke(projectId: String): ProjectDocument? =
        repository.loadDocument(projectId)
}

class RecoverProjectUseCase @Inject constructor(
    private val repository: ProjectRepository,
) {
    suspend fun recoverable(): ProjectSummary? = repository.findRecoverable()

    suspend operator fun invoke(projectId: String, keepPendingEdits: Boolean): ProjectDocument? =
        repository.resolveRecovery(projectId, keepPendingEdits)
}

/** Autosave tick: stores the pending snapshot without touching the canonical JSON. */
class AutosaveProjectUseCase @Inject constructor(
    private val repository: ProjectRepository,
) {
    suspend operator fun invoke(document: ProjectDocument) = repository.savePending(document)
}

/** Explicit save (toolbar / back navigation): commits pending as canonical. */
class CommitProjectUseCase @Inject constructor(
    private val repository: ProjectRepository,
) {
    suspend operator fun invoke(document: ProjectDocument) = repository.commit(document)
}

class RenameProjectUseCase @Inject constructor(
    private val repository: ProjectRepository,
) {
    suspend operator fun invoke(projectId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) repository.rename(projectId, trimmed)
    }
}

class DeleteProjectUseCase @Inject constructor(
    private val repository: ProjectRepository,
) {
    suspend operator fun invoke(projectId: String) = repository.delete(projectId)
}

class DuplicateProjectUseCase @Inject constructor(
    private val repository: ProjectRepository,
) {
    suspend operator fun invoke(projectId: String, newName: String): String? =
        repository.duplicate(projectId, newName)
}
