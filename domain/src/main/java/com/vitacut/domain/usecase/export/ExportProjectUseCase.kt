package com.vitacut.domain.usecase.export

import com.vitacut.core.model.ExportSettings
import com.vitacut.domain.repository.ExportRepository
import com.vitacut.domain.repository.ProjectRepository
import javax.inject.Inject

/**
 * Start-export flow: guarantees the worker reads a consistent snapshot by committing pending
 * edits first, then hands the job to the export scheduler.
 *
 * Returns the export record id; the UI observes [ExportRepository.observeProgress] and the
 * record list for terminal states.
 */
class ExportProjectUseCase @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val exportRepository: ExportRepository,
) {
    suspend operator fun invoke(
        projectId: String,
        settings: ExportSettings,
        destinationTreeUri: String? = null,
    ): Long {
        // Flush the open document's latest state so background export never races autosave.
        projectRepository.loadDocument(projectId)?.let { document ->
            projectRepository.commit(document)
        }
        return exportRepository.startExport(projectId, settings, destinationTreeUri)
    }
}
