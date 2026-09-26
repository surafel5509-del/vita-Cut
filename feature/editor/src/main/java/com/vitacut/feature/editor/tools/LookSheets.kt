package com.vitacut.feature.editor.tools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitacut.core.designsystem.R
import com.vitacut.core.designsystem.components.VitaButton
import com.vitacut.core.designsystem.components.VitaChipItem
import com.vitacut.core.designsystem.components.VitaChipRow
import com.vitacut.core.designsystem.components.VitaLabeledSlider
import com.vitacut.core.designsystem.components.VitaOutlinedButton
import com.vitacut.core.model.AspectRatio
import com.vitacut.core.model.CanvasBackground
import com.vitacut.core.model.CaptionAnimation
import com.vitacut.core.model.CaptionPosition
import com.vitacut.core.model.ColorAdjustments
import com.vitacut.core.model.EffectCategory
import com.vitacut.core.model.EffectKind
import com.vitacut.core.model.KeyframeProperty
import com.vitacut.core.model.Project
import com.vitacut.core.model.TransitionCategory
import com.vitacut.core.model.TransitionKind
import com.vitacut.core.model.VideoClipItem
import com.vitacut.core.rendering.filters.FilterLibrary
import com.vitacut.core.timeline.TimelineQueries
import com.vitacut.feature.editor.EditorViewModel
import com.vitacut.feature.editor.withKaraoke

/**
 * Catalog display names (filters, effects, transitions) are derived from their stable ids.
 * They are product vocabulary (like "Teal & Orange"); every *chrome* string around them comes
 * from resources.
 */
private fun catalogLabel(id: String): String =
    id.replace('_', ' ').replace('-', ' ').split(' ')
        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

// region Filters

@Composable
internal fun FiltersSheet(viewModel: EditorViewModel) {
    val item = viewModel.selectedItem() as? VideoClipItem
    SheetTitle(stringResource(R.string.tool_filters))
    if (item == null) {
        EmptySelectionHint()
        return
    }
    val categories = remember { FilterLibrary.ALL.map { it.category }.distinct() }
    var category by remember { mutableStateOf(categories.first()) }
    VitaChipRow(
        chips = categories.map { cat ->
            VitaChipItem(cat.name, catalogLabel(cat.name), cat == category)
        },
        onChipClick = { id -> category = categories.first { it.name == id } },
    )
    val filters = remember(category) { FilterLibrary.ALL.filter { it.category == category } }
    CatalogGrid(
        cells = listOf(CatalogCell("none", stringResource(R.string.action_reset), item.filter.filterId == null)) +
            filters.map { filter ->
                CatalogCell(filter.id, catalogLabel(filter.id), item.filter.filterId == filter.id)
            },
        onClick = { id -> viewModel.setFilter(id.takeIf { it != "none" }) },
    )
    if (item.filter.filterId != null) {
        VitaLabeledSlider(
            label = stringResource(R.string.tool_intensity),
            value = item.filter.intensity,
            onValueChange = viewModel::setFilterIntensity,
            modifier = Modifier.padding(horizontal = 20.dp),
            valueRange = 0f..1f,
            valueText = "${(item.filter.intensity * 100).toInt()}%",
        )
    }
}

// endregion

// region Effects

@Composable
internal fun EffectsSheet(viewModel: EditorViewModel) {
    val item = viewModel.selectedItem() as? VideoClipItem
    SheetTitle(stringResource(R.string.tool_effects))
    if (item == null) {
        EmptySelectionHint()
        return
    }
    var category by remember { mutableStateOf(EffectCategory.TRENDING) }
    VitaChipRow(
        chips = EffectCategory.entries.map { cat ->
            VitaChipItem(cat.name, catalogLabel(cat.name), cat == category)
        },
        onChipClick = { id -> category = EffectCategory.valueOf(id) },
    )
    val kinds = remember(category) { EffectKind.entries.filter { it.category == category } }
    CatalogGrid(
        cells = kinds.map { kind ->
            CatalogCell(kind.name, catalogLabel(kind.name), item.effects.any { it.kind == kind })
        },
        onClick = { id -> viewModel.addEffect(EffectKind.valueOf(id)) },
    )
    item.effects.forEach { effect ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                catalogLabel(effect.kind.name),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = effect.enabled,
                onCheckedChange = { enabled ->
                    viewModel.update("effect.toggle") { project ->
                        com.vitacut.core.timeline.TimelineEngine.setEffectEnabled(
                            project, item.id, effect.id, enabled,
                        )
                    }
                },
            )
            androidx.compose.material3.TextButton(onClick = { viewModel.removeEffect(effect.id) }) {
                Text(stringResource(R.string.action_delete))
            }
        }
        VitaLabeledSlider(
            label = stringResource(R.string.tool_intensity),
            value = effect.intensity,
            onValueChange = { intensity -> viewModel.setEffectIntensity(effect.id, intensity) },
            modifier = Modifier.padding(horizontal = 20.dp),
            valueRange = 0f..1f,
            valueText = "${(effect.intensity * 100).toInt()}%",
        )
    }
}

// endregion

// region Adjust (color grading)

private data class AdjustmentEntry(
    val labelRes: Int,
    val get: (ColorAdjustments) -> Float,
    val set: (ColorAdjustments, Float) -> ColorAdjustments,
)

private val ADJUSTMENTS = listOf(
    AdjustmentEntry(R.string.grade_brightness, { it.brightness }, { a, v -> a.copy(brightness = v) }),
    AdjustmentEntry(R.string.grade_exposure, { it.exposure }, { a, v -> a.copy(exposure = v) }),
    AdjustmentEntry(R.string.grade_contrast, { it.contrast }, { a, v -> a.copy(contrast = v) }),
    AdjustmentEntry(R.string.grade_saturation, { it.saturation }, { a, v -> a.copy(saturation = v) }),
    AdjustmentEntry(R.string.grade_vibrance, { it.vibrance }, { a, v -> a.copy(vibrance = v) }),
    AdjustmentEntry(R.string.grade_temperature, { it.temperature }, { a, v -> a.copy(temperature = v) }),
    AdjustmentEntry(R.string.grade_tint, { it.tint }, { a, v -> a.copy(tint = v) }),
    AdjustmentEntry(R.string.grade_highlights, { it.highlights }, { a, v -> a.copy(highlights = v) }),
    AdjustmentEntry(R.string.grade_shadows, { it.shadows }, { a, v -> a.copy(shadows = v) }),
    AdjustmentEntry(R.string.grade_whites, { it.whites }, { a, v -> a.copy(whites = v) }),
    AdjustmentEntry(R.string.grade_blacks, { it.blacks }, { a, v -> a.copy(blacks = v) }),
    AdjustmentEntry(R.string.grade_fade, { it.fade }, { a, v -> a.copy(fade = v) }),
    AdjustmentEntry(R.string.grade_sharpness, { it.sharpen }, { a, v -> a.copy(sharpen = v) }),
    AdjustmentEntry(R.string.grade_clarity, { it.clarity }, { a, v -> a.copy(clarity = v) }),
    AdjustmentEntry(R.string.grade_vignette, { it.vignette }, { a, v -> a.copy(vignette = v) }),
    AdjustmentEntry(R.string.grade_grain, { it.grain }, { a, v -> a.copy(grain = v) }),
)

@Composable
internal fun AdjustSheet(viewModel: EditorViewModel) {
    val item = viewModel.selectedItem() as? VideoClipItem
    SheetTitle(stringResource(R.string.tool_adjust))
    if (item == null) {
        EmptySelectionHint()
        return
    }
    val adjustments = item.grading.adjustments
    ADJUSTMENTS.forEach { entry ->
        VitaLabeledSlider(
            label = stringResource(entry.labelRes),
            value = entry.get(adjustments),
            onValueChange = { value ->
                viewModel.setGrading(
                    item.grading.copy(adjustments = entry.set(adjustments, value)),
                )
            },
            modifier = Modifier.padding(horizontal = 20.dp),
            valueRange = -1f..1f,
            valueText = String.format("%+.2f", entry.get(adjustments)),
        )
    }
    Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        VitaOutlinedButton(
            text = stringResource(R.string.action_reset),
            onClick = { viewModel.setGrading(item.grading.copy(adjustments = ColorAdjustments.DEFAULT)) },
        )
    }
    Text(
        stringResource(R.string.grade_curves) + " · " + stringResource(R.string.grade_hsl) +
            " · " + stringResource(R.string.grade_lut),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
}

// endregion

// region Transitions

@Composable
internal fun TransitionSheet(viewModel: EditorViewModel) {
    val item = viewModel.selectedItem() as? VideoClipItem
    SheetTitle(stringResource(R.string.tool_transition))
    if (item == null) {
        EmptySelectionHint()
        return
    }
    var atEnd by remember { mutableStateOf(true) }
    VitaChipRow(
        chips = listOf(
            VitaChipItem("out", "Out ▸", atEnd),
            VitaChipItem("in", "◂ In", !atEnd),
        ),
        onChipClick = { atEnd = it == "out" },
    )
    TransitionCategory.entries.forEach { category ->
        val kinds = TransitionKind.entries.filter { it.category == category }
        if (kinds.isEmpty()) return@forEach
        Text(
            catalogLabel(category.name),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 20.dp, top = 8.dp),
        )
        val current = if (atEnd) item.transitionOut else item.transitionIn
        VitaChipRow(
            chips = buildList {
                add(VitaChipItem("none", stringResource(R.string.tool_transition_none), current == null))
                kinds.forEach { kind ->
                    add(VitaChipItem(kind.name, catalogLabel(kind.name), current?.kind == kind))
                }
            },
            onChipClick = { id ->
                viewModel.setTransition(
                    id.takeIf { it != "none" }?.let { TransitionKind.valueOf(it) },
                    atEnd = atEnd,
                )
            },
        )
    }
    val current = if (atEnd) item.transitionOut else item.transitionIn
    if (current != null) {
        VitaLabeledSlider(
            label = stringResource(R.string.tool_transition),
            value = current.durationUs / 1_000_000f,
            onValueChange = { seconds ->
                viewModel.setTransition(current.kind, atEnd, (seconds * 1_000_000).toLong())
            },
            modifier = Modifier.padding(horizontal = 20.dp),
            valueRange = 0.2f..3f,
            valueText = String.format("%.1fs", current.durationUs / 1_000_000f),
        )
    }
}

// endregion

// region Canvas

@Composable
internal fun CanvasSheet(viewModel: EditorViewModel, project: Project) {
    SheetTitle(stringResource(R.string.tool_canvas))
    Text(
        stringResource(R.string.tool_canvas_ratio),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(start = 20.dp),
    )
    VitaChipRow(
        chips = listOf(
            AspectRatio.RATIO_16_9 to "16:9",
            AspectRatio.RATIO_9_16 to "9:16",
            AspectRatio.RATIO_1_1 to "1:1",
            AspectRatio.RATIO_4_5 to "4:5",
            AspectRatio.RATIO_3_4 to "3:4",
            AspectRatio.RATIO_21_9 to "21:9",
        ).map { (ratio, label) ->
            VitaChipItem(ratio.name, label, project.canvas.aspectRatio == ratio)
        },
        onChipClick = { id ->
            viewModel.setCanvas(
                project.canvas.copy(aspectRatio = AspectRatio.valueOf(id)),
            )
        },
    )
    Text(
        stringResource(R.string.tool_canvas_background),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(start = 20.dp, top = 8.dp),
    )
    val bg = project.canvas.background
    VitaChipRow(
        chips = listOf(
            VitaChipItem("black", stringResource(R.string.tool_bg_black), bg is CanvasBackground.Black),
            VitaChipItem("white", stringResource(R.string.tool_bg_white), bg is CanvasBackground.Color && bg.argb == -1),
            VitaChipItem("blur", stringResource(R.string.tool_bg_blur), bg is CanvasBackground.BlurFill),
            VitaChipItem(
                "aurora",
                stringResource(R.string.tool_bg_gradient),
                bg is CanvasBackground.Gradient,
            ),
        ),
        onChipClick = { id ->
            viewModel.setCanvasBackground(
                when (id) {
                    "black" -> CanvasBackground.Black
                    "white" -> CanvasBackground.Color(-1)
                    "blur" -> CanvasBackground.BlurFill
                    else -> CanvasBackground.Gradient(0xFF1E1B4B.toInt(), 0xFF0E7490.toInt())
                },
            )
        },
    )
}

// endregion

// region Captions

@Composable
internal fun CaptionsSheet(viewModel: EditorViewModel, project: Project) {
    val context = LocalContext.current
    SheetTitle(stringResource(R.string.captions_title))
    val captionSet = project.captions

    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            readTextFromUri(context, uri)?.let(viewModel::importCaptions)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VitaButton(
            text = stringResource(R.string.captions_auto),
            onClick = viewModel::generateCaptions,
            modifier = Modifier.weight(1f),
        )
        VitaOutlinedButton(
            text = stringResource(R.string.captions_import),
            onClick = { importer.launch(arrayOf("*/*")) },
            modifier = Modifier.weight(1f),
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(stringResource(R.string.captions_title), style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = captionSet.enabled,
            onCheckedChange = viewModel::setCaptionsEnabled,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(stringResource(R.string.captions_karaoke), style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = captionSet.style.animation == CaptionAnimation.KARAOKE,
            onCheckedChange = { enabled ->
                viewModel.setCaptionStyle(
                    captionSet.withKaraoke(enabled).style,
                )
            },
        )
    }
    Text(
        stringResource(R.string.captions_position),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(start = 20.dp, top = 8.dp),
    )
    VitaChipRow(
        chips = CaptionPosition.entries.map { position ->
            VitaChipItem(position.name, catalogLabel(position.name), captionSet.style.position == position)
        },
        onChipClick = { id ->
            viewModel.setCaptionStyle(
                captionSet.style.copy(position = CaptionPosition.valueOf(id)),
            )
        },
    )
    VitaLabeledSlider(
        label = stringResource(R.string.captions_style),
        value = captionSet.style.sizeFraction,
        onValueChange = { size ->
            viewModel.setCaptionStyle(captionSet.style.copy(sizeFraction = size))
        },
        modifier = Modifier.padding(horizontal = 20.dp),
        valueRange = 0.02f..0.12f,
        valueText = "${(captionSet.style.sizeFraction * 100).toInt()}%",
    )

    // Edit the cue under the playhead.
    val playhead = viewModel.uiState.value.playheadUs
    val cue = captionSet.captions.firstOrNull { playhead in it.startUs until it.endUs }
    if (cue != null) {
        var text by androidx.compose.runtime.mutableStateOf(cue.text)
        androidx.compose.material3.OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            label = { Text(stringResource(R.string.captions_review_hint)) },
        )
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VitaButton(
                text = stringResource(R.string.action_apply),
                onClick = { viewModel.updateCaptionText(cue.id, text) },
            )
            VitaOutlinedButton(
                text = stringResource(R.string.tool_split),
                onClick = { viewModel.splitCaption(cue.id) },
            )
            VitaOutlinedButton(
                text = stringResource(R.string.action_delete),
                onClick = { viewModel.deleteCaption(cue.id) },
            )
        }
    }

    if (captionSet.captions.isNotEmpty()) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VitaOutlinedButton(
                text = stringResource(R.string.captions_export_srt),
                onClick = {
                    viewModel.exportCaptions(vtt = false)?.let { shareFile(context, it, "text/plain") }
                },
            )
            VitaOutlinedButton(
                text = stringResource(R.string.captions_export_vtt),
                onClick = {
                    viewModel.exportCaptions(vtt = true)?.let { shareFile(context, it, "text/plain") }
                },
            )
        }
    }
}

// endregion

// region AI

@Composable
internal fun AiSheet(viewModel: EditorViewModel, project: Project) {
    SheetTitle(stringResource(R.string.ai_title))
    Column(modifier = Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        VitaButton(
            text = stringResource(R.string.ai_remove_silence),
            onClick = viewModel::removeSilences,
            modifier = Modifier.fillMaxWidth(),
        )
        VitaButton(
            text = stringResource(R.string.ai_beat_sync),
            onClick = viewModel::beatSync,
            modifier = Modifier.fillMaxWidth(),
        )
        VitaButton(
            text = stringResource(R.string.ai_auto_cut),
            onClick = viewModel::autoCut,
            modifier = Modifier.fillMaxWidth(),
        )
        VitaButton(
            text = stringResource(R.string.ai_detect_objects),
            onClick = viewModel::detectObjects,
            modifier = Modifier.fillMaxWidth(),
        )
        VitaButton(
            text = stringResource(R.string.ai_remove_bg),
            onClick = viewModel::removeBackgroundOfSelected,
            enabled = viewModel.selectedItem() is VideoClipItem,
            modifier = Modifier.fillMaxWidth(),
        )
        val stickerSelected = viewModel.selectedItem() is com.vitacut.core.model.StickerItem
        VitaButton(
            text = stringResource(R.string.tool_tracking),
            onClick = {
                viewModel.trackSelectedSticker(
                    com.vitacut.core.ai.tracking.NormalizedRegion(0.4f, 0.4f, 0.2f, 0.2f),
                )
            },
            enabled = stickerSelected,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Text(
        stringResource(R.string.ai_auto_reframe),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp),
    )
    VitaChipRow(
        chips = listOf(
            "16:9" to 16f / 9f,
            "9:16" to 9f / 16f,
            "1:1" to 1f,
            "4:5" to 4f / 5f,
        ).map { (label, aspect) ->
            VitaChipItem(label, label, project.canvas.aspectRatio.ratio == aspect)
        },
        onChipClick = { label ->
            val aspect = when (label) {
                "16:9" -> 16f / 9f
                "9:16" -> 9f / 16f
                "1:1" -> 1f
                else -> 4f / 5f
            }
            viewModel.autoReframe(aspect)
        },
    )
}

// endregion

// region Keyframes

@Composable
internal fun KeyframeSheet(viewModel: EditorViewModel) {
    val item = viewModel.selectedItem()
    SheetTitle(stringResource(R.string.tool_keyframe_add))
    if (item == null) {
        EmptySelectionHint()
        return
    }
    listOf(
        KeyframeProperty.POSITION_X,
        KeyframeProperty.POSITION_Y,
        KeyframeProperty.SCALE_X,
        KeyframeProperty.SCALE_Y,
        KeyframeProperty.ROTATION,
        KeyframeProperty.OPACITY,
        KeyframeProperty.VOLUME,
        KeyframeProperty.FILTER_INTENSITY,
    ).forEach { property ->
        val keyframes = when (item) {
            is VideoClipItem -> item.keyframes
            is com.vitacut.core.model.AudioClipItem -> item.keyframes
            is com.vitacut.core.model.TextItem -> item.keyframes
            is com.vitacut.core.model.StickerItem -> item.keyframes
        }
        val track = keyframes.trackFor(property)
        val localTime = viewModel.uiState.value.playheadUs - item.timelineStartUs
        val hasKeyHere = track?.keyframes?.any { kotlin.math.abs(it.timeUs - localTime) < 50_000L } == true
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                catalogLabel(property.name),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.TextButton(
                onClick = {
                    val current = track?.valueAt(localTime) ?: defaultValueFor(property, item)
                    viewModel.addKeyframeAtPlayhead(property, current)
                },
            ) {
                Text(
                    stringResource(
                        if (hasKeyHere) R.string.action_apply else R.string.tool_keyframe_add,
                    ),
                )
            }
            androidx.compose.material3.TextButton(
                onClick = { viewModel.removeKeyframeAtPlayhead(property) },
                enabled = hasKeyHere,
            ) {
                Text(stringResource(R.string.tool_keyframe_remove))
            }
        }
    }
}

private fun defaultValueFor(property: KeyframeProperty, item: com.vitacut.core.model.TimelineItem): Float =
    when (property) {
        KeyframeProperty.POSITION_X, KeyframeProperty.POSITION_Y -> 0f
        KeyframeProperty.SCALE_X, KeyframeProperty.SCALE_Y -> 1f
        KeyframeProperty.ROTATION -> 0f
        KeyframeProperty.OPACITY -> when (item) {
            is VideoClipItem -> item.transform.opacity
            else -> 1f
        }

        KeyframeProperty.VOLUME -> when (item) {
            is VideoClipItem -> item.volume
            is com.vitacut.core.model.AudioClipItem -> item.volume
            else -> 1f
        }

        KeyframeProperty.FILTER_INTENSITY -> when (item) {
            is VideoClipItem -> item.filter.intensity
            else -> 1f
        }

        else -> 0f
    }

// endregion

/** Silence-aware helper kept here so sheets can query the timeline without extra imports. */
@Suppress("unused")
private fun isSilentAt(project: Project, timeUs: Long): Boolean =
    TimelineQueries.audioAt(project, timeUs).isEmpty()

internal data class CatalogCell(val id: String, val label: String, val selected: Boolean)

@Composable
internal fun CatalogGrid(
    cells: List<CatalogCell>,
    onClick: (String) -> Unit,
    columns: Int = 4,
) {
    val palette = listOf(
        listOf(Color(0xFF1E293B), Color(0xFF0EA5E9)),
        listOf(Color(0xFF3B0764), Color(0xFFD946EF)),
        listOf(Color(0xFF431407), Color(0xFFF97316)),
        listOf(Color(0xFF052E16), Color(0xFF22C55E)),
        listOf(Color(0xFF111827), Color(0xFF6366F1)),
        listOf(Color(0xFF3F1D0A), Color(0xFFFBBF24)),
    )
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        cells.chunked(columns).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { cell ->
                    val colors = palette[(cell.id.hashCode().and(0x7fffffff)) % palette.size]
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(0.85f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Brush.verticalGradient(colors))
                            .clickable { onClick(cell.id) }
                            .then(
                                if (cell.selected) {
                                    Modifier.padding(0.dp)
                                } else {
                                    Modifier
                                },
                            ),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        if (cell.selected) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .background(Color(0xFF22D3EE).copy(alpha = 0.22f)),
                            )
                        }
                        Text(
                            cell.label,
                            color = Color.White,
                            fontSize = 10.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(6.dp),
                        )
                    }
                }
                repeat(columns - row.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 8.dp))
        }
    }
}
