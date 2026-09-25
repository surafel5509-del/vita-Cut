package com.vitacut.feature.editor.tools

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.vitacut.core.designsystem.R
import com.vitacut.core.designsystem.components.VitaButton
import com.vitacut.core.designsystem.components.VitaChipItem
import com.vitacut.core.designsystem.components.VitaChipRow
import com.vitacut.core.designsystem.components.VitaLabeledSlider
import com.vitacut.core.designsystem.components.VitaOutlinedButton
import com.vitacut.core.model.AudioClipItem
import com.vitacut.core.model.Project
import com.vitacut.core.model.SpeedCurvePreset
import com.vitacut.core.model.SpeedModel
import com.vitacut.core.model.StickerSource
import com.vitacut.core.model.TextItem
import com.vitacut.core.model.TextStyleLibrary
import com.vitacut.core.model.VideoClipItem
import com.vitacut.core.rendering.overlay.BuiltInStickers
import com.vitacut.feature.editor.EditorSheet
import com.vitacut.feature.editor.EditorViewModel
import java.io.File

private fun catalogLabel(id: String): String =
    id.replace('_', ' ').replace('-', ' ').split(' ')
        .joinToString(" ") { part -> part.replaceFirstChar { c -> c.uppercase() } }

/** Routes the active [EditorSheet] to its panel inside a modal bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolSheetHost(
    sheet: EditorSheet?,
    viewModel: EditorViewModel,
    project: Project,
    onDismiss: () -> Unit,
) {
    if (sheet == null) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            when (sheet) {
                EditorSheet.MEDIA -> MediaSheet(viewModel)
                EditorSheet.CLIP -> ClipSheet(viewModel)
                EditorSheet.SPEED -> SpeedSheet(viewModel)
                EditorSheet.AUDIO -> AudioSheet(viewModel)
                EditorSheet.TEXT -> TextSheet(viewModel)
                EditorSheet.STICKER -> StickerSheet(viewModel)
                EditorSheet.FILTERS -> FiltersSheet(viewModel)
                EditorSheet.EFFECTS -> EffectsSheet(viewModel)
                EditorSheet.ADJUST -> AdjustSheet(viewModel)
                EditorSheet.TRANSITION -> TransitionSheet(viewModel)
                EditorSheet.CANVAS -> CanvasSheet(viewModel, project)
                EditorSheet.CAPTIONS -> CaptionsSheet(viewModel, project)
                EditorSheet.AI -> AiSheet(viewModel, project)
                EditorSheet.KEYFRAME -> KeyframeSheet(viewModel)
                EditorSheet.TRANSFORM -> TransformSheet(viewModel)
                EditorSheet.MASK -> MaskSheet(viewModel)
                EditorSheet.CHROMA -> ChromaSheet(viewModel)
                EditorSheet.OVERLAY -> OverlaySheet(viewModel)
            }
        }
    }
}

@Composable
internal fun SheetTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 8.dp),
    )
}

// region Media

@Composable
internal fun MediaSheet(viewModel: EditorViewModel) {
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9),
    ) { uris -> viewModel.addMedia(uris) }
    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> viewModel.addMedia(uris) }

    SheetTitle(stringResource(R.string.editor_add_media))
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VitaButton(
            text = stringResource(R.string.editor_add_video),
            onClick = {
                photoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                )
            },
            modifier = Modifier.weight(1f),
        )
        VitaButton(
            text = stringResource(R.string.editor_add_photo),
            onClick = {
                photoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            modifier = Modifier.weight(1f),
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VitaOutlinedButton(
            text = stringResource(R.string.editor_add_music),
            onClick = { audioPicker.launch(arrayOf("audio/*")) },
            modifier = Modifier.weight(1f),
        )
        val uiRecording = viewModel.uiState.value.recorderState
        VitaOutlinedButton(
            text = stringResource(R.string.editor_record_voiceover),
            onClick = {
                if (uiRecording == com.vitacut.core.media.record.RecorderState.IDLE) {
                    viewModel.startVoiceover()
                } else {
                    viewModel.stopVoiceover()
                }
            },
            modifier = Modifier.weight(1f),
        )
    }
}

// endregion

// region Clip / speed / audio

@Composable
internal fun ClipSheet(viewModel: EditorViewModel) {
    val item = viewModel.selectedItem()
    SheetTitle(stringResource(R.string.tool_edit))
    if (item == null) {
        EmptySelectionHint()
        return
    }
    VitaChipRow(
        chips = listOf(
            VitaChipItem("split", stringResource(R.string.tool_split), false),
            VitaChipItem("duplicate", stringResource(R.string.tool_duplicate_clip), false),
            VitaChipItem("delete", stringResource(R.string.tool_delete_clip), false),
        ) + if (item is VideoClipItem) {
            listOf(
                VitaChipItem("reverse", stringResource(R.string.tool_reverse), item.reversed),
                VitaChipItem("freeze", stringResource(R.string.tool_freeze_frame), false),
                VitaChipItem("detach", stringResource(R.string.tool_detach_audio), false),
                VitaChipItem("paste", stringResource(R.string.tool_paste_look), false),
                VitaChipItem("rot90", stringResource(R.string.tool_rotate_90), false),
            )
        } else emptyList(),
        onChipClick = { id ->
            when (id) {
                "split" -> viewModel.splitAtPlayhead()
                "duplicate" -> viewModel.duplicateSelected()
                "delete" -> viewModel.deleteSelected()
                "reverse" -> viewModel.reverseSelectedClip()
                "freeze" -> viewModel.freezeFrame()
                "detach" -> viewModel.detachAudio()
                "paste" -> viewModel.pasteAttributesFromPrevious()
                "rot90" -> viewModel.rotateSelected(90f)
            }
        },
    )
}

@Composable
internal fun SpeedSheet(viewModel: EditorViewModel) {
    val item = viewModel.selectedItem() as? VideoClipItem
    SheetTitle(stringResource(R.string.tool_speed))
    if (item == null) {
        EmptySelectionHint()
        return
    }
    val current = (item.speed as? SpeedModel.Constant)?.speed
    VitaChipRow(
        chips = SpeedModel.SPEED_CHOICES.map { choice ->
            VitaChipItem(
                choice.toString(),
                stringResource(R.string.tool_speed_value, choice),
                current == choice,
            )
        },
        onChipClick = { viewModel.setSpeed(it.toFloatOrNull() ?: 1f) },
    )
    Text(
        stringResource(R.string.tool_speed_curves),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp),
    )
    val currentPreset = (item.speed as? SpeedModel.Curve)?.preset
    VitaChipRow(
        chips = SpeedCurvePreset.entries.map { preset ->
            VitaChipItem(
                preset.name,
                stringResource(curvePresetLabel(preset)),
                currentPreset == preset || (preset == SpeedCurvePreset.NORMAL && item.speed is SpeedModel.Constant),
            )
        },
        onChipClick = { id ->
            viewModel.setSpeedPreset(SpeedCurvePreset.valueOf(id))
        },
    )
}

@Composable
private fun curvePresetLabel(preset: SpeedCurvePreset): Int = when (preset) {
    SpeedCurvePreset.NORMAL -> R.string.tool_curve_normal
    SpeedCurvePreset.MONTAGE -> R.string.tool_curve_montage
    SpeedCurvePreset.BULLET -> R.string.tool_curve_bullet
    SpeedCurvePreset.HERO -> R.string.tool_curve_hero
    SpeedCurvePreset.JUMP_CUT -> R.string.tool_curve_jumpcut
    SpeedCurvePreset.FLASH_IN -> R.string.tool_curve_flash_in
    SpeedCurvePreset.FLASH_OUT -> R.string.tool_curve_flash_out
    SpeedCurvePreset.CUSTOM -> R.string.tool_curve_custom
}

@Composable
internal fun AudioSheet(viewModel: EditorViewModel) {
    val item = viewModel.selectedItem()
    SheetTitle(stringResource(R.string.tool_audio))
    if (item == null) {
        EmptySelectionHint()
        return
    }
    val volume = when (item) {
        is VideoClipItem -> item.volume
        is AudioClipItem -> item.volume
        else -> 1f
    }
    val fadeIn = when (item) {
        is VideoClipItem -> item.fadeInUs
        is AudioClipItem -> item.fadeInUs
        else -> 0L
    }
    val fadeOut = when (item) {
        is VideoClipItem -> item.fadeOutUs
        is AudioClipItem -> item.fadeOutUs
        else -> 0L
    }
    VitaLabeledSlider(
        label = stringResource(R.string.tool_volume),
        value = volume,
        onValueChange = viewModel::setVolume,
        modifier = Modifier.padding(horizontal = 20.dp),
        valueRange = 0f..2f,
        valueText = "${(volume * 100).toInt()}%",
    )
    VitaLabeledSlider(
        label = stringResource(R.string.tool_fade_in),
        value = fadeIn / 1_000_000f,
        onValueChange = { viewModel.setFades((it * 1_000_000).toLong(), fadeOut) },
        modifier = Modifier.padding(horizontal = 20.dp),
        valueRange = 0f..5f,
        valueText = String.format("%.1fs", fadeIn / 1_000_000f),
    )
    VitaLabeledSlider(
        label = stringResource(R.string.tool_fade_out),
        value = fadeOut / 1_000_000f,
        onValueChange = { viewModel.setFades(fadeIn, (it * 1_000_000).toLong()) },
        modifier = Modifier.padding(horizontal = 20.dp),
        valueRange = 0f..5f,
        valueText = String.format("%.1fs", fadeOut / 1_000_000f),
    )
    if (item is AudioClipItem) {
        val fx = item.audioEffects
        Text(
            stringResource(R.string.tool_audio_fx),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp),
        )
        VitaLabeledSlider(
            label = stringResource(R.string.tool_bass),
            value = fx.bass,
            onValueChange = { viewModel.setAudioEffects(fx.copy(bass = it)) },
            modifier = Modifier.padding(horizontal = 20.dp),
            valueRange = -1f..1f,
            valueText = String.format("%+.2f", fx.bass),
        )
        VitaLabeledSlider(
            label = stringResource(R.string.tool_treble),
            value = fx.treble,
            onValueChange = { viewModel.setAudioEffects(fx.copy(treble = it)) },
            modifier = Modifier.padding(horizontal = 20.dp),
            valueRange = -1f..1f,
            valueText = String.format("%+.2f", fx.treble),
        )
        VitaLabeledSlider(
            label = stringResource(R.string.tool_reverb),
            value = fx.reverb,
            onValueChange = { viewModel.setAudioEffects(fx.copy(reverb = it)) },
            modifier = Modifier.padding(horizontal = 20.dp),
            valueRange = 0f..1f,
            valueText = "${(fx.reverb * 100).toInt()}%",
        )
        VitaLabeledSlider(
            label = stringResource(R.string.tool_echo),
            value = fx.echo,
            onValueChange = { viewModel.setAudioEffects(fx.copy(echo = it)) },
            modifier = Modifier.padding(horizontal = 20.dp),
            valueRange = 0f..1f,
            valueText = "${(fx.echo * 100).toInt()}%",
        )
        VitaLabeledSlider(
            label = stringResource(R.string.tool_noise_reduction),
            value = fx.noiseReduction,
            onValueChange = { viewModel.setAudioEffects(fx.copy(noiseReduction = it)) },
            modifier = Modifier.padding(horizontal = 20.dp),
            valueRange = 0f..1f,
            valueText = "${(fx.noiseReduction * 100).toInt()}%",
        )
        VitaChipRow(
            chips = listOf(
                VitaChipItem("voice", stringResource(R.string.tool_voice_enhance), fx.voiceEnhance),
                VitaChipItem("norm", stringResource(R.string.tool_normalize), fx.normalize),
            ),
            onChipClick = { id ->
                when (id) {
                    "voice" -> viewModel.setAudioEffects(fx.copy(voiceEnhance = !fx.voiceEnhance))
                    "norm" -> viewModel.setAudioEffects(fx.copy(normalize = !fx.normalize))
                }
            },
        )
    }
}

// endregion

// region Text & stickers

@Composable
internal fun TextSheet(viewModel: EditorViewModel) {
    val item = viewModel.selectedItem() as? TextItem
    SheetTitle(stringResource(R.string.tool_text))
    var text by remember(item?.id) { mutableStateOf(item?.text ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        placeholder = { Text(stringResource(R.string.tool_text_hint)) },
        minLines = 2,
    )
    Row(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (item == null) {
            VitaButton(
                text = stringResource(R.string.tool_text),
                onClick = { viewModel.addText(text.ifBlank { "Text" }) },
            )
        } else {
            VitaButton(
                text = stringResource(R.string.action_apply),
                onClick = { viewModel.updateSelectedText(text) },
            )
        }
    }
    if (item != null) {
        Text(
            stringResource(R.string.tool_text_presets),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 20.dp),
        )
        VitaChipRow(
            chips = TextStyleLibrary.ALL.map { preset ->
                VitaChipItem(preset.id, catalogLabel(preset.id), item.style == preset.style)
            },
            onChipClick = viewModel::applyTextPreset,
        )
        Text(
            stringResource(R.string.tool_text_style),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 20.dp, top = 8.dp),
        )
        VitaLabeledSlider(
            label = stringResource(R.string.tool_text_style),
            value = item.style.sizeFraction,
            onValueChange = { size ->
                viewModel.setSelectedTextStyle { it.copy(sizeFraction = size) }
            },
            modifier = Modifier.padding(horizontal = 20.dp),
            valueRange = 0.02f..0.2f,
            valueText = "${(item.style.sizeFraction * 100).toInt()}%",
        )
        val colors = listOf(
            0xFFFFFFFF.toInt() to "White",
            0xFF000000.toInt() to "Black",
            0xFFFFD54F.toInt() to "Gold",
            0xFF22D3EE.toInt() to "Cyan",
            0xFFF472B6.toInt() to "Pink",
        )
        VitaChipRow(
            chips = colors.mapIndexed { index, (argb, name) ->
                VitaChipItem(index.toString(), name, item.style.colorArgb == argb)
            },
            onChipClick = { index ->
                viewModel.setSelectedTextStyle { it.copy(colorArgb = colors[index.toInt()].first) }
            },
        )
        Text(
            stringResource(R.string.tool_text_animation),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 20.dp),
        )
        VitaChipRow(
            chips = com.vitacut.core.model.TextAnimationIn.entries.map { anim ->
                VitaChipItem(anim.name, anim.name.lowercase(), item.animations.inAnimation == anim)
            },
            onChipClick = { id ->
                viewModel.setSelectedTextAnimation(
                    inAnim = com.vitacut.core.model.TextAnimationIn.valueOf(id),
                )
            },
        )
        VitaChipRow(
            chips = com.vitacut.core.model.TextAnimationLoop.entries.map { anim ->
                VitaChipItem(anim.name, anim.name.lowercase(), item.animations.loopAnimation == anim)
            },
            onChipClick = { id ->
                viewModel.setSelectedTextAnimation(
                    loop = com.vitacut.core.model.TextAnimationLoop.valueOf(id),
                )
            },
        )
        VitaChipRow(
            chips = com.vitacut.core.model.TextAnimationOut.entries.map { anim ->
                VitaChipItem("out_${anim.name}", "out ${anim.name.lowercase()}", item.animations.outAnimation == anim)
            },
            onChipClick = { id ->
                viewModel.setSelectedTextAnimation(
                    outAnim = com.vitacut.core.model.TextAnimationOut.valueOf(id.removePrefix("out_")),
                )
            },
        )
        VitaChipRow(
            chips = listOf("default", "serif", "monospace").map { key ->
                VitaChipItem(key, catalogLabel(key), item.style.fontFamilyKey == key)
            },
            onChipClick = { key ->
                viewModel.setSelectedTextStyle { it.copy(fontFamilyKey = key) }
            },
        )
        VitaChipRow(
            chips = listOf(
                VitaChipItem("bold", "B", item.style.bold),
                VitaChipItem("italic", "I", item.style.italic),
                VitaChipItem("underline", "U", item.style.underline),
            ),
            onChipClick = { id ->
                viewModel.setSelectedTextStyle { style ->
                    when (id) {
                        "bold" -> style.copy(bold = !style.bold)
                        "italic" -> style.copy(italic = !style.italic)
                        else -> style.copy(underline = !style.underline)
                    }
                }
            },
        )
    }
}

private val EMOJI_CHOICES = listOf(
    "😀", "😂", "😍", "🤩", "😎", "🥳", "😭", "🤯",
    "❤️", "🔥", "⭐", "✨", "💯", "👍", "👏", "🙌",
    "🎉", "🎵", "🎬", "📸", "🌈", "☀️", "🌙", "⚡",
    "👀", "🍿", "☕", "🚀", "💎", "🏆", "🌸", "🦋",
)

@Composable
internal fun StickerSheet(viewModel: EditorViewModel) {
    SheetTitle(stringResource(R.string.tool_sticker))
    Text(
        stringResource(R.string.tool_sticker),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(start = 20.dp),
    )
    VitaChipRow(
        chips = EMOJI_CHOICES.map { emoji -> VitaChipItem(emoji, emoji, false) },
        onChipClick = { emoji -> viewModel.addSticker(StickerSource.Emoji(emoji)) },
    )
    VitaChipRow(
        chips = BuiltInStickers.ALL_KEYS.map { key -> VitaChipItem(key, key, false) },
        onChipClick = { key -> viewModel.addSticker(StickerSource.BuiltIn(key)) },
    )
}

@Composable
internal fun EmptySelectionHint() {
    Text(
        stringResource(R.string.editor_no_clip_selected),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(20.dp),
    )
}

// endregion

/** Shares a file through the app FileProvider (used by caption exports). */
internal fun shareFile(context: android.content.Context, file: File, mime: String) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }
}

/** Resolves a caption-subtitle import to plain text (null on unreadable files). */
internal fun readTextFromUri(context: android.content.Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
}.getOrNull()
