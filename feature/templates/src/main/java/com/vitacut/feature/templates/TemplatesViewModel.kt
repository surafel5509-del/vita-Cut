package com.vitacut.feature.templates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vitacut.core.common.logging.VitaLog
import com.vitacut.core.model.Template
import com.vitacut.domain.repository.TemplateMeta
import com.vitacut.domain.repository.TemplateRepository
import com.vitacut.domain.usecase.template.InstantiateTemplateUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TemplatesUiState(
    val templates: List<TemplateMeta> = emptyList(),
    val isLoading: Boolean = true,
    val errorKey: String? = null,
    val category: String? = null,
)

@HiltViewModel
class TemplatesViewModel @Inject constructor(
    private val repository: TemplateRepository,
    private val instantiateTemplate: InstantiateTemplateUseCase,
) : ViewModel() {

    private val local = MutableStateFlow(TemplatesUiState())

    val uiState: StateFlow<TemplatesUiState> = combine(
        repository.observeTemplates(),
        local,
    ) { templates, ui ->
        val filtered = if (ui.category == null) templates
        else templates.filter { it.template.category.name == ui.category }
        ui.copy(templates = filtered, isLoading = false)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    fun setCategory(category: String?) {
        local.value = local.value.copy(category = category)
    }

    fun useTemplate(template: Template, projectName: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            repository.recordUse(template.id.value)
            runCatching { instantiateTemplate(template, projectName) }
                .onSuccess { document ->
                    if (document != null) {
                        onCreated(document.project.id.value)
                    } else {
                        local.value = local.value.copy(errorKey = "error_unknown")
                    }
                }
                .onFailure {
                    VitaLog.e("TemplatesViewModel", "Instantiate failed", it)
                    local.value = local.value.copy(errorKey = "error_unknown")
                }
        }
    }

    fun toggleFavorite(templateId: String, favorite: Boolean) {
        viewModelScope.launch { runCatching { repository.setFavorite(templateId, favorite) } }
    }

    fun deleteTemplate(templateId: String) {
        viewModelScope.launch { runCatching { repository.delete(templateId) } }
    }

    fun consumeError() {
        local.value = local.value.copy(errorKey = null)
    }
}
