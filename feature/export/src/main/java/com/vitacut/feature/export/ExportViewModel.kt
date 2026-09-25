package com.vitacut.feature.export

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vitacut.core.common.device.DeviceCapabilities
import com.vitacut.core.common.logging.VitaLog
import com.vitacut.core.datastore.SettingsStore
import com.vitacut.core.export.ExportPlanner
import com.vitacut.core.export.ExportValidation
import com.vitacut.core.export.snapshot
import com.vitacut.core.model.ExportSettings
import com.vitacut.domain.repository.ExportJobStatus
import com.vitacut.domain.repository.ExportRecord
import com.vitacut.domain.repository.ProjectRepository
import com.vitacut.domain.usecase.export.ExportProjectUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ExportUiState(
    val projectId: String = "",
    val projectName: String = "",
    val durationUs: Long = 0L,
    val settings: ExportSettings = ExportSettings.DEFAULT,
    /** String-resource keys of the capability fallbacks that will be applied. */
    val fallbackKeys: List<String> = emptyList(),
    val estimatedBytes: Long = 0L,
    val invalidKey: String? = null,
    val outputWidth: Int = 0,
    val outputHeight: Int = 0,
    val isExporting: Boolean = false,
    val progressPercent: Int = 0,
    val exportStartedAtMs: Long = 0L,
    /** Terminal result of the most recent attempt. */
    val result: ExportRecord? = null,
    val errorKey: String? = null,
)

@HiltViewModel
class ExportViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val projectRepository: ProjectRepository,
    private val settingsStore: SettingsStore,
    private val exportProject: ExportProjectUseCase,
    private val exportRepository: com.vitacut.domain.repository.ExportRepository,
    private val capabilities: DeviceCapabilities,
) : ViewModel() {

    private val projectId: String = savedStateHandle.get<String>("projectId").orEmpty()

    private val local = MutableStateFlow(ExportUiState(projectId = projectId))

    val uiState: StateFlow<ExportUiState> = combine(
        local,
        exportRepository.observeProgress(),
    ) { ui, progress ->
        ui.copy(
            progressPercent = progress ?: if (ui.isExporting) ui.progressPercent else 0,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        viewModelScope.launch {
            val defaults = settingsStore.settings.first().defaultExportSettings
            val summary = projectRepository.getSummary(projectId)
            local.value = local.value.copy(
                projectName = summary?.name.orEmpty(),
                durationUs = summary?.durationUs ?: 0L,
                settings = defaults,
            )
            revalidate()
            observeRecords()
        }
    }

    private suspend fun observeRecords() {
        exportRepository.observeRecords(projectId).collect { records ->
            val latest = records.firstOrNull()
            val current = local.value
            when (latest?.status) {
                ExportJobStatus.SUCCEEDED -> local.value = current.copy(
                    isExporting = false,
                    result = latest,
                    progressPercent = 100,
                )

                ExportJobStatus.FAILED, ExportJobStatus.CANCELLED -> local.value = current.copy(
                    isExporting = false,
                    result = latest,
                    errorKey = latest.errorMessage?.substringBefore(':'),
                )

                ExportJobStatus.RUNNING, ExportJobStatus.PENDING ->
                    if (!current.isExporting) {
                        local.value = current.copy(isExporting = true)
                    }

                null -> Unit
            }
        }
    }

    fun updateSettings(transform: (ExportSettings) -> ExportSettings) {
        local.value = local.value.copy(settings = transform(local.value.settings))
        viewModelScope.launch { revalidate() }
    }

    /** Recomputes the plan preview: fallbacks, output size, estimated bytes, hard errors. */
    private suspend fun revalidate() {
        val document = projectRepository.loadDocument(projectId)
        val project = document?.project
        if (project == null) {
            local.value = local.value.copy(invalidKey = "error_project_unreadable")
            return
        }
        when (val validation = ExportPlanner.plan(
            local.value.settings,
            project,
            capabilities.snapshot(),
        )) {
            is ExportValidation.Invalid -> local.value = local.value.copy(
                invalidKey = validation.messageKey,
                fallbackKeys = emptyList(),
            )

            is ExportValidation.Ready -> {
                val plan = validation.plan
                local.value = local.value.copy(
                    invalidKey = null,
                    fallbackKeys = plan.fallbacks.map { it.messageKey },
                    outputWidth = plan.outputWidth,
                    outputHeight = plan.outputHeight,
                    estimatedBytes = ExportPlanner.estimatedOutputBytes(plan, project.durationUs),
                    durationUs = project.durationUs,
                )
            }
        }
    }

    fun startExport() {
        if (local.value.invalidKey != null || local.value.isExporting) return
        viewModelScope.launch {
            local.value = local.value.copy(
                isExporting = true,
                progressPercent = 0,
                exportStartedAtMs = System.currentTimeMillis(),
                result = null,
                errorKey = null,
            )
            runCatching {
                val treeUri = settingsStore.current().exportLocationUri
                exportProject(projectId, local.value.settings, treeUri)
            }.onFailure {
                VitaLog.e("ExportViewModel", "Failed to enqueue export", it)
                local.value = local.value.copy(isExporting = false, errorKey = "error_export_failed")
            }
        }
    }

    fun cancelExport() {
        exportRepository.cancelExport()
        local.value = local.value.copy(isExporting = false, progressPercent = 0)
    }

    fun consumeError() {
        local.value = local.value.copy(errorKey = null)
    }

    fun consumeResult() {
        local.value = local.value.copy(result = null)
    }

    /** Elapsed-based ETA in ms, or null when progress is too early to estimate. */
    fun etaMs(state: ExportUiState): Long? {
        if (!state.isExporting || state.progressPercent in 0..2) return null
        val elapsed = System.currentTimeMillis() - state.exportStartedAtMs
        return elapsed * (100 - state.progressPercent) / state.progressPercent
    }
}
