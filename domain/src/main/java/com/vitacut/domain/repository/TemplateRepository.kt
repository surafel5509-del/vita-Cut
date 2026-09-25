package com.vitacut.domain.repository

import com.vitacut.core.model.Template
import kotlinx.coroutines.flow.Flow

/** A template plus the user-specific gallery state stored alongside it. */
data class TemplateMeta(
    val template: Template,
    val isFavorite: Boolean = false,
    val useCount: Int = 0,
)

/** Template gallery boundary (bundled asset templates + user-saved ones). */
interface TemplateRepository {

    fun observeTemplates(): Flow<List<TemplateMeta>>

    suspend fun getTemplate(templateId: String): Template?

    /** Persists the current project as a reusable template (slots derived from its tracks). */
    suspend fun saveFromProject(projectId: String, nameKey: String, category: String): String?

    suspend fun setFavorite(templateId: String, favorite: Boolean)

    suspend fun recordUse(templateId: String)

    suspend fun delete(templateId: String)
}
