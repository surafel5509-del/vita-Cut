package com.vitacut.data.repository

import android.content.Context
import com.vitacut.core.common.logging.VitaLog
import com.vitacut.core.database.dao.ProjectDao
import com.vitacut.core.database.dao.TemplateDao
import com.vitacut.core.database.entity.TemplateEntity
import com.vitacut.core.model.Template
import com.vitacut.core.model.TemplateId
import com.vitacut.core.model.TemplateSlot
import com.vitacut.core.model.TextItem
import com.vitacut.core.model.TrackKind
import com.vitacut.core.model.VideoClipItem
import com.vitacut.core.model.projectFromJson
import com.vitacut.core.model.templateFromJson
import com.vitacut.core.model.toJson
import com.vitacut.domain.repository.TemplateMeta
import com.vitacut.domain.repository.TemplateRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Template gallery: bundled templates ship as JSON in module assets; user-saved templates live
 * in app-private files. The DB stores only gallery state (favorites, use counts) keyed by
 * template id — full documents are never duplicated into Room.
 */
@Singleton
class TemplateRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val templateDao: TemplateDao,
    private val projectDao: ProjectDao,
) : TemplateRepository {

    override fun observeTemplates(): Flow<List<TemplateMeta>> = flow {
        val templates = loadAllTemplates()
        syncMetadata(templates)
        emitAll(
            templateDao.observeAll().map { entities ->
                val meta = entities.associateBy { it.templateId }
                templates.map { template ->
                    TemplateMeta(
                        template = template,
                        isFavorite = meta[template.id.value]?.isFavorite == true,
                        useCount = meta[template.id.value]?.useCount ?: 0,
                    )
                }
            },
        )
    }.flowOn(Dispatchers.IO)

    override suspend fun getTemplate(templateId: String): Template? = withContext(Dispatchers.IO) {
        runCatching { loadAllTemplates().firstOrNull { it.id.value == templateId } }.getOrNull()
    }

    override suspend fun saveFromProject(projectId: String, nameKey: String, category: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val entity = projectDao.findById(projectId) ?: return@runCatching null
                val project = projectFromJson(entity.pendingJson ?: entity.projectJson)
                    ?: return@runCatching null

                val slots = mutableListOf<TemplateSlot>()
                var order = 0
                val videoTrack = project.tracks.firstOrNull { it.kind == TrackKind.VIDEO }
                videoTrack?.items?.sortedBy { it.timelineStartUs }?.forEach { item ->
                    if (item is VideoClipItem) {
                        slots += TemplateSlot(
                            order = order++,
                            kind = TemplateSlot.SlotKind.MEDIA,
                            durationUs = item.durationUs,
                        )
                    }
                }
                project.tracks.filter { it.kind == TrackKind.TEXT }
                    .flatMap { it.items }
                    .filterIsInstance<TextItem>()
                    .sortedBy { it.timelineStartUs }
                    .forEach { item ->
                        slots += TemplateSlot(
                            order = order++,
                            kind = TemplateSlot.SlotKind.TEXT,
                            durationUs = item.durationUs,
                            textKey = item.text,
                            textStyle = item.style,
                        )
                    }
                if (slots.none { it.kind == TemplateSlot.SlotKind.MEDIA }) return@runCatching null

                val template = Template(
                    id = TemplateId("user-${System.currentTimeMillis()}"),
                    nameKey = nameKey,
                    category = runCatching {
                        com.vitacut.core.model.TemplateCategory.valueOf(category)
                    }.getOrDefault(com.vitacut.core.model.TemplateCategory.VLOG),
                    canvas = project.canvas,
                    frameRate = project.frameRate,
                    slots = slots,
                )
                userTemplateFile(template.id.value).writeText(template.toJson())
                templateDao.upsertAll(listOf(template.toEntity(preserve = templateDao.findById(template.id.value))))
                template.id.value
            }.getOrElse {
                VitaLog.w(TAG, "saveFromProject failed: ${it.message}")
                null
            }
        }

    override suspend fun setFavorite(templateId: String, favorite: Boolean) {
        withContext(Dispatchers.IO) {
            runCatching {
                if (templateDao.findById(templateId) == null) {
                    // State for a not-yet-synced template: insert a placeholder row.
                    val template = getTemplate(templateId) ?: return@runCatching
                    templateDao.upsertAll(listOf(template.toEntity(preserve = null)))
                }
                templateDao.setFavorite(templateId, favorite)
            }
        }
    }

    override suspend fun recordUse(templateId: String) {
        withContext(Dispatchers.IO) {
            runCatching { templateDao.recordUse(templateId, System.currentTimeMillis()) }
        }
    }

    override suspend fun delete(templateId: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                val file = userTemplateFile(templateId)
                if (file.exists()) file.delete()
                templateDao.deleteById(templateId)
            }
        }
    }

    // region loading & sync

    private var cachedBundled: List<Template>? = null

    private fun loadAllTemplates(): List<Template> {
        val bundled = cachedBundled ?: runCatching {
            context.assets.list(ASSET_DIR)?.mapNotNull { name ->
                runCatching {
                    context.assets.open("$ASSET_DIR/$name").bufferedReader().use {
                        templateFromJson(it.readText())
                    }
                }.getOrNull()
            }.orEmpty()
        }.getOrDefault(emptyList()).also { cachedBundled = it }

        val user = runCatching {
            userTemplateDir().listFiles()?.mapNotNull { file ->
                runCatching { templateFromJson(file.readText()) }.getOrNull()
            }.orEmpty()
        }.getOrDefault(emptyList())

        return bundled + user
    }

    /** Inserts metadata rows for templates the DB hasn't seen, preserving existing state. */
    private suspend fun syncMetadata(templates: List<Template>) {
        runCatching {
            val missing = templates.filter { templateDao.findById(it.id.value) == null }
            if (missing.isNotEmpty()) {
                templateDao.upsertAll(missing.map { it.toEntity(preserve = null) })
            }
        }.onFailure { VitaLog.w(TAG, "Template sync failed: ${it.message}") }
    }

    private fun userTemplateDir(): File =
        File(context.filesDir, "templates").apply { mkdirs() }

    private fun userTemplateFile(templateId: String): File =
        File(userTemplateDir(), "$templateId.json")

    private fun Template.toEntity(preserve: TemplateEntity?) = TemplateEntity(
        templateId = id.value,
        name = nameKey,
        category = category.name,
        thumbnailKey = thumbnailKey,
        durationUs = totalDurationUs,
        canvasAspect = canvas.aspect,
        isFavorite = preserve?.isFavorite ?: false,
        useCount = preserve?.useCount ?: 0,
        lastUsedMs = preserve?.lastUsedMs ?: 0L,
    )

    // endregion

    private companion object {
        const val TAG = "TemplateRepository"
        const val ASSET_DIR = "templates"
    }
}
