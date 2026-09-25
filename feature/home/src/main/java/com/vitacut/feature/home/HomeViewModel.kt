package com.vitacut.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vitacut.core.common.logging.VitaLog
import com.vitacut.core.model.AspectRatio
import com.vitacut.domain.repository.ProjectSummary
import com.vitacut.domain.usecase.project.CreateProjectUseCase
import com.vitacut.domain.usecase.project.DeleteProjectUseCase
import com.vitacut.domain.usecase.project.DuplicateProjectUseCase
import com.vitacut.domain.usecase.project.ObserveProjectsUseCase
import com.vitacut.domain.usecase.project.RecoverProjectUseCase
import com.vitacut.domain.usecase.project.RenameProjectUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val projects: List<ProjectSummary> = emptyList(),
    val query: String = "",
    val isLoading: Boolean = true,
    /** Set when a crashed session left recoverable edits; drives the recovery dialog. */
    val recoverable: ProjectSummary? = null,
    val showNewProjectSheet: Boolean = false,
    val actionErrorKey: String? = null,
) {
    val visibleProjects: List<ProjectSummary>
        get() = if (query.isBlank()) {
            projects
        } else {
            projects.filter { it.name.contains(query, ignoreCase = true) }
        }
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val observeProjects: ObserveProjectsUseCase,
    private val createProject: CreateProjectUseCase,
    private val renameProject: RenameProjectUseCase,
    private val deleteProject: DeleteProjectUseCase,
    private val duplicateProject: DuplicateProjectUseCase,
    private val recoverProject: RecoverProjectUseCase,
) : ViewModel() {

    private val local = MutableStateFlow(
        HomeUiState(recoverable = null),
    )

    val uiState: StateFlow<HomeUiState> = combine(
        observeProjects(),
        local,
    ) { projects, ui ->
        ui.copy(projects = projects, isLoading = false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        viewModelScope.launch {
            val recoverable = runCatching { recoverProject.recoverable() }.getOrNull()
            if (recoverable != null) local.value = local.value.copy(recoverable = recoverable)
        }
    }

    fun onQueryChange(query: String) {
        local.value = local.value.copy(query = query)
    }

    fun showNewProjectSheet(show: Boolean) {
        local.value = local.value.copy(showNewProjectSheet = show)
    }

    fun createProject(name: String, aspect: AspectRatio, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { createProject(name.ifBlank { "Untitled" }, aspect) }
                .onSuccess { document ->
                    local.value = local.value.copy(showNewProjectSheet = false)
                    onCreated(document.project.id.value)
                }
                .onFailure {
                    VitaLog.e("HomeViewModel", "Create failed", it)
                    local.value = local.value.copy(actionErrorKey = "error_unknown")
                }
        }
    }

    fun rename(projectId: String, name: String) {
        viewModelScope.launch { runCatching { renameProject(projectId, name) } }
    }

    fun delete(projectId: String) {
        viewModelScope.launch { runCatching { deleteProject(projectId) } }
    }

    fun duplicate(summary: ProjectSummary, copyNameTemplate: (String) -> String) {
        viewModelScope.launch {
            runCatching { duplicateProject(summary.projectId, copyNameTemplate(summary.name)) }
        }
    }

    fun resolveRecovery(keepEdits: Boolean, onResolved: (String) -> Unit) {
        val target = local.value.recoverable ?: return
        viewModelScope.launch {
            val document = runCatching { recoverProject(target.projectId, keepEdits) }.getOrNull()
            local.value = local.value.copy(recoverable = null)
            if (document != null) onResolved(target.projectId)
        }
    }

    fun consumeError() {
        local.value = local.value.copy(actionErrorKey = null)
    }
}
