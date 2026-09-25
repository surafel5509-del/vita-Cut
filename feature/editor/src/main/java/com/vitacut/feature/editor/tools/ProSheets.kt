package com.vitacut.feature.editor.tools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vitacut.core.designsystem.R
import com.vitacut.core.designsystem.components.VitaButton
import com.vitacut.core.designsystem.components.VitaChipItem
import com.vitacut.core.designsystem.components.VitaChipRow
import com.vitacut.core.designsystem.components.VitaLabeledSlider
import com.vitacut.core.designsystem.components.VitaOutlinedButton
import com.vitacut.core.model.BlendMode
import com.vitacut.core.model.ContentFit
import com.vitacut.core.model.CropSettings
import com.vitacut.core.model.MaskSettings
import com.vitacut.core.model.MaskShape
import com.vitacut.core.model.StickerItem
import com.vitacut.core.model.TextItem
import com.vitacut.core.model.VideoClipItem
import com.vitacut.feature.editor.EditorViewModel

private fun catalogLabel(id: String): String =
    id.replace('_', ' ').replace('-', ' ').split(' ')
        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

@Composable
internal fun TransformSheet(viewModel: EditorViewModel) {
    val item = viewModel.selectedItem()
    SheetTitle(stringResource(R.string.tool_transform))
    if (item == null) {
        EmptySelectionHint()
        return
    }
    val transform = when (item) {
        is VideoClipItem -> item.transform
        is TextItem -> item.transform
        is StickerItem -> item.transform
        else -> {
            EmptySelectionHint()
            return
        }
    }
    VitaLabeledSlider(
        label = stringResource(R.string.tool_position_x),
        value = transform.translationX,
        onValueChange = { v -> viewModel.updateTransform { it.copy(translationX = v) } },
        modifier = Modifier.padding(horizontal = 20.dp),
        valueRange = -1f..1f,
        valueText = String.format("%+.2f", transform.translationX),
    )
    VitaLabeledSlider(
        label = stringResource(R.string.tool_position_y),
        value = transform.translationY,
        onValueChange = { v -> viewModel.updateTransform { it.copy(translationY = v) } },
        modifier = Modifier.padding(horizontal = 20.dp),
        valueRange = -1f..1f,
        valueText = String.format("%+.2f", transform.translationY),
    )
    VitaLabeledSlider(
        label = stringResource(R.string.tool_scale),
        value = transform.scaleX,
        onValueChange = { v -> viewModel.updateTransform { it.copy(scaleX = v, scaleY = v) } },
        modifier = Modifier.padding(horizontal = 20.dp),
        valueRange = 0.1f..3f,
        valueText = String.format("%.2fx", transform.scaleX),
    )
    VitaLabeledSlider(
        label = stringResource(R.string.tool_rotation),
        value = transform.rotationDegrees,
        onValueChange = { v -> viewModel.updateTransform { it.copy(rotationDegrees = v) } },
        modifier = Modifier.padding(horizontal = 20.dp),
        valueRange = -180f..180f,
        valueText = "${transform.rotationDegrees.toInt()}°",
    )
    VitaLabeledSlider(
        label = stringResource(R.string.tool_opacity),
        value = transform.opacity,
        onValueChange = { v -> viewModel.updateTransform { it.copy(opacity = v) } },
        modifier = Modifier.padding(horizontal = 20.dp),
        valueRange = 0f..1f,
        valueText = "${(transform.opacity * 100).toInt()}%",
    )
    VitaChipRow(
        chips = listOf(
            VitaChipItem("rot90", stringResource(R.string.tool_rotate_90), false),
            VitaChipItem("fliph", stringResource(R.string.tool_flip_h), transform.flipHorizontal),
            VitaChipItem("flipv", stringResource(R.string.tool_flip_v), transform.flipVertical),
        ),
        onChipClick = { id ->
            when (id) {
                "rot90" -> viewModel.rotateSelected(90f)
                "fliph" -> viewModel.flipSelected(horizontal = true)
                "flipv" -> viewModel.flipSelected(horizontal = false)
            }
        },
    )
    if (item is VideoClipItem) {
        Text(
            stringResource(R.string.tool_blend_mode),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 20.dp, top = 8.dp),
        )
        VitaChipRow(
            chips = BlendMode.entries.map { mode ->
                VitaChipItem(mode.name, catalogLabel(mode.name), item.blendMode == mode)
            },
            onChipClick = { viewModel.setBlendMode(BlendMode.valueOf(it)) },
        )
        Text(
            stringResource(R.string.tool_fit),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 20.dp, top = 8.dp),
        )
        VitaChipRow(
            chips = ContentFit.entries.map { fit ->
                VitaChipItem(fit.name, catalogLabel(fit.name), item.contentFit == fit)
            },
            onChipClick = { viewModel.setContentFit(ContentFit.valueOf(it)) },
        )
        VitaLabeledSlider(
            label = stringResource(R.string.tool_crop),
            value = 1f - item.crop.widthFraction,
            onValueChange = { inset ->
                val edge = (inset * 0.4f).coerceIn(0f, 0.4f)
                viewModel.setCrop(CropSettings(edge, edge, 1f - edge, 1f - edge))
            },
            modifier = Modifier.padding(horizontal = 20.dp),
            valueRange = 0f..1f,
            valueText = "${((1f - item.crop.widthFraction) * 100).toInt()}%",
        )
    }
}

@Composable
internal fun MaskSheet(viewModel: EditorViewModel) {
    val item = viewModel.selectedItem() as? VideoClipItem
    SheetTitle(stringResource(R.string.tool_mask))
    if (item == null) {
        EmptySelectionHint()
        return
    }
    val mask = item.mask
    VitaChipRow(
        chips = MaskShape.entries.filter { it != MaskShape.FREEFORM }.map { shape ->
            VitaChipItem(shape.name, catalogLabel(shape.name), mask.shape == shape)
        },
        onChipClick = { id ->
            viewModel.setMask(mask.copy(shape = MaskShape.valueOf(id)))
        },
    )
    if (mask.shape != MaskShape.NONE) {
        VitaLabeledSlider(
            label = stringResource(R.string.tool_feather),
            value = mask.feather,
            onValueChange = { viewModel.setMask(mask.copy(feather = it)) },
            modifier = Modifier.padding(horizontal = 20.dp),
            valueRange = 0f..1f,
            valueText = "${(mask.feather * 100).toInt()}%",
        )
        VitaLabeledSlider(
            label = stringResource(R.string.tool_scale),
            value = mask.scaleX,
            onValueChange = { v -> viewModel.setMask(mask.copy(scaleX = v, scaleY = v)) },
            modifier = Modifier.padding(horizontal = 20.dp),
            valueRange = 0.1f..1.5f,
            valueText = String.format("%.2f", mask.scaleX),
        )
        VitaChipRow(
            chips = listOf(
                VitaChipItem("invert", stringResource(R.string.tool_invert), mask.invert),
            ),
            onChipClick = { viewModel.setMask(mask.copy(invert = !mask.invert)) },
        )
    }
}

@Composable
internal fun ChromaSheet(viewModel: EditorViewModel) {
    val item = viewModel.selectedItem() as? VideoClipItem
    SheetTitle(stringResource(R.string.tool_chroma_key))
    if (item == null) {
        EmptySelectionHint()
        return
    }
    val chroma = item.chromaKey
    VitaChipRow(
        chips = listOf(
            VitaChipItem("off", stringResource(R.string.action_reset), !chroma.enabled),
            VitaChipItem("on", stringResource(R.string.tool_chroma_key), chroma.enabled),
        ),
        onChipClick = { id ->
            viewModel.setChromaKey(chroma.copy(enabled = id == "on"))
        },
    )
    val keys = listOf(
        0xFF00B140.toInt() to stringResource(R.string.tool_chroma_green),
        0xFF1D4ED8.toInt() to stringResource(R.string.tool_chroma_blue),
        0xFFE11D48.toInt() to stringResource(R.string.tool_chroma_red),
        0xFF000000.toInt() to stringResource(R.string.tool_bg_black),
    )
    VitaChipRow(
        chips = keys.mapIndexed { index, (argb, label) ->
            VitaChipItem(index.toString(), label, chroma.keyColorArgb == argb)
        },
        onChipClick = { index ->
            viewModel.setChromaKey(
                chroma.copy(enabled = true, keyColorArgb = keys[index.toInt()].first),
            )
        },
    )
    VitaLabeledSlider(
        label = stringResource(R.string.tool_intensity),
        value = chroma.intensity,
        onValueChange = { viewModel.setChromaKey(chroma.copy(intensity = it, enabled = true)) },
        modifier = Modifier.padding(horizontal = 20.dp),
        valueRange = 0f..1f,
        valueText = "${(chroma.intensity * 100).toInt()}%",
    )
    VitaLabeledSlider(
        label = stringResource(R.string.tool_feather),
        value = chroma.feather,
        onValueChange = { viewModel.setChromaKey(chroma.copy(feather = it, enabled = true)) },
        modifier = Modifier.padding(horizontal = 20.dp),
        valueRange = 0f..1f,
        valueText = "${(chroma.feather * 100).toInt()}%",
    )
    VitaLabeledSlider(
        label = stringResource(R.string.tool_spill),
        value = chroma.spillSuppression,
        onValueChange = { viewModel.setChromaKey(chroma.copy(spillSuppression = it, enabled = true)) },
        modifier = Modifier.padding(horizontal = 20.dp),
        valueRange = 0f..1f,
        valueText = "${(chroma.spillSuppression * 100).toInt()}%",
    )
}

@Composable
internal fun OverlaySheet(viewModel: EditorViewModel) {
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(6),
    ) { uris -> viewModel.addOverlay(uris) }
    SheetTitle(stringResource(R.string.tool_overlay))
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VitaButton(
            text = stringResource(R.string.tool_pip),
            onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            },
            modifier = Modifier.weight(1f),
        )
        VitaOutlinedButton(
            text = stringResource(R.string.editor_add_photo),
            onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            modifier = Modifier.weight(1f),
        )
    }
    Text(
        stringResource(R.string.tool_overlay_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}
