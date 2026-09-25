package com.vitacut.domain.usecase.template

import com.vitacut.core.model.Project
import com.vitacut.core.model.ProjectId
import com.vitacut.core.model.Template
import com.vitacut.core.model.TemplateSlot
import com.vitacut.core.model.TextItem
import com.vitacut.core.model.Track
import com.vitacut.core.model.TrackKind
import com.vitacut.domain.repository.ProjectRepository
import com.vitacut.core.timeline.ProjectDocument
import javax.inject.Inject

/**
 * Turns a [Template] into a real, editable [Project]:
 * - canvas & frame rate are taken from the template,
 * - MEDIA slots become an empty main video track (the editor shows slot durations as the
 *   "replace media" guide — templates keep structure, the user supplies the media),
 * - TEXT slots become pre-styled, pre-timed [TextItem]s on a text track,
 * - suggested slot transitions are stored on the template document for the editor's
 *   "apply suggested transitions" action.
 *
 * Honest by construction: nothing references media that doesn't exist on this device.
 */
class InstantiateTemplateUseCase @Inject constructor(
    private val projectRepository: ProjectRepository,
) {

    fun buildProject(template: Template, name: String, nowMs: Long = System.currentTimeMillis()): Project {
        val textSlots = template.slots.filter { it.kind == TemplateSlot.SlotKind.TEXT }
        val hasMediaSlots = template.slots.any { it.kind == TemplateSlot.SlotKind.MEDIA }

        val tracks = buildList {
            if (hasMediaSlots) {
                add(Track(kind = TrackKind.VIDEO, order = 0))
            }
            if (textSlots.isNotEmpty()) {
                val items = textSlots.map { slot ->
                    // Text slots are timed by the cumulative duration of preceding slots.
                    val start = template.slots
                        .filter { it.order < slot.order }
                        .sumOf { it.durationUs }
                    TextItem(
                        text = slot.textKey.orEmpty(),
                        timelineStartUs = start,
                        durationUs = slot.durationUs,
                        style = slot.textStyle ?: com.vitacut.core.model.TextStyle(),
                    )
                }
                add(Track(kind = TrackKind.TEXT, order = 1, items = items))
            }
        }

        return Project(
            id = ProjectId(),
            name = name,
            createdAtMs = nowMs,
            updatedAtMs = nowMs,
            canvas = template.canvas,
            frameRate = template.frameRate,
            tracks = tracks,
        )
    }

    suspend operator fun invoke(template: Template, name: String): ProjectDocument? =
        projectRepository.createFromTemplate(buildProject(template, name), name)
}
