package com.vitacut.feature.editor

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Redo
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.vitacut.core.common.time.formatTimecode
import com.vitacut.core.designsystem.R
import com.vitacut.core.designsystem.components.VitaConfirmDialog
import com.vitacut.core.designsystem.components.VitaErrorState
import com.vitacut.core.designsystem.components.VitaLoading
import com.vitacut.core.designsystem.strings.VitaStrings
import com.vitacut.core.media.record.RecorderState
import com.vitacut.core.model.VideoClipItem
import com.vitacut.feature.editor.timeline.TimelinePanel
import com.vitacut.feature.editor.tools.ToolSheetHost

/**
 * Studio editor chrome: preview, transport, multi-track timeline, context tools, dock.
 */
@Composable
fun EditorScreen(
    onExit: () -> Unit,
    onExport: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EditorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val project by viewModel.project.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val controller = viewModel.previewController

    BackHandler { viewModel.requestBack(onExit) }

    uiState.messageKey?.let { key ->
        LaunchedEffect(key, uiState.messageArgs) {
            val text = if (uiState.messageArgs.isEmpty()) {
                VitaStrings.localized(context, key)
            } else {
                VitaStrings.localized(context, key, *uiState.messageArgs.toTypedArray())
            }
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    if (uiState.isLoading) {
        VitaLoading(modifier = modifier, label = stringResource(R.string.editor_loading))
        return
    }
    if (uiState.loadErrorKey != null || project == null) {
        VitaErrorState(
            title = stringResource(R.string.error_project_unreadable),
            message = VitaStrings.localized(context, uiState.loadErrorKey ?: "error_project_unreadable"),
            modifier = modifier,
            retryLabel = stringResource(R.string.action_back),
            onRetry = onExit,
        )
        return
    }
    val currentProject = project!!

    LaunchedEffect(currentProject, uiState.playheadUs, uiState.isPlaying) {
        if (!uiState.isPlaying) {
            controller.invalidateEffects(currentProject, uiState.playheadUs)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        EditorTopBar(
            onClose = { viewModel.requestBack(onExit) },
            onExport = { onExport(currentProject.id.value) },
            onHelp = {
                Toast.makeText(
                    context,
                    context.getString(R.string.editor_help),
                    Toast.LENGTH_SHORT,
                ).show()
            },
            resolutionLabel = currentProject.resolutionLabel,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        player = controller.player
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = { view ->
                    if (view.player !== controller.player) view.player = controller.player
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(currentProject.canvas.aspectRatio.ratio.coerceIn(0.3f, 3.5f)),
            )

            uiState.busyKey?.let { key ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
                        Text(
                            text = if (uiState.busyPercent >= 0) {
                                "${VitaStrings.localized(context, key)} ${uiState.busyPercent}%"
                            } else {
                                VitaStrings.localized(context, key)
                            },
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }

            if (uiState.recorderState != RecorderState.IDLE) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black.copy(alpha = 0.75f))
                        .clickable { viewModel.stopVoiceover() }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = null, tint = Color.Red, modifier = Modifier.size(16.dp))
                    Text(stringResource(R.string.editor_recording), color = Color.White, fontSize = 12.sp)
                    Text(
                        formatTimecode(uiState.recordDurationMs * 1000L, showMillis = false),
                        color = Color.White,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        TransportBar(
            isPlaying = uiState.isPlaying,
            playheadUs = uiState.playheadUs,
            durationUs = uiState.durationUs,
            canUndo = uiState.canUndo,
            canRedo = uiState.canRedo,
            dirty = uiState.dirty,
            saving = uiState.saving,
            onPlay = viewModel::togglePlay,
            onSplit = viewModel::splitAtPlayhead,
            onOverlay = { viewModel.openSheet(EditorSheet.OVERLAY) },
            onUndo = viewModel::undo,
            onRedo = viewModel::redo,
            onAdd = { viewModel.openSheet(EditorSheet.MEDIA) },
        )

        TimelinePanel(
            project = currentProject,
            playheadUs = uiState.playheadUs,
            selectedItemId = uiState.selectedItemId,
            isPlaying = uiState.isPlaying,
            onSeek = viewModel::seekTo,
            onSelect = viewModel::selectItem,
            onMove = viewModel::moveItem,
            onTrim = viewModel::trimItem,
            thumbnailProvider = controller.thumbnailProvider,
            waveformExtractor = controller.waveformExtractor,
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
        )

        val selectedVideo = viewModel.selectedItem() as? VideoClipItem
        if (selectedVideo != null) {
            ClipContextBar(
                reversed = selectedVideo.reversed,
                opacity = selectedVideo.transform.opacity,
                stabilized = !selectedVideo.crop.isFullFrame && selectedVideo.transform.scaleX >= 1.07f,
                onSplit = viewModel::splitAtPlayhead,
                onStabilize = viewModel::stabilizeSelected,
                onOpacity = { viewModel.openSheet(EditorSheet.TRANSFORM) },
                onOpacityChange = viewModel::setOpacity,
                onReverse = viewModel::reverseSelectedClip,
                onFreeze = viewModel::freezeFrame,
                onVoice = { viewModel.openSheet(EditorSheet.AUDIO) },
                onSpeed = { viewModel.openSheet(EditorSheet.SPEED) },
                onCutout = { viewModel.openSheet(EditorSheet.CHROMA) },
                onAi = { viewModel.openSheet(EditorSheet.AI) },
            )
        }

        StudioDock(onTool = viewModel::openSheet)
    }

    ToolSheetHost(
        sheet = uiState.activeSheet,
        viewModel = viewModel,
        project = currentProject,
        onDismiss = { viewModel.openSheet(null) },
    )

    if (uiState.showDiscardDialog) {
        VitaConfirmDialog(
            title = stringResource(R.string.editor_discard_changes_title),
            message = stringResource(R.string.editor_discard_changes_message),
            confirmText = stringResource(R.string.action_save),
            dismissText = stringResource(R.string.action_close),
            onConfirm = viewModel::saveAndExit,
            onDismiss = viewModel::dismissDiscardDialog,
        )
    }
}

@Composable
private fun EditorTopBar(
    onClose: () -> Unit,
    onExport: () -> Unit,
    onHelp: () -> Unit,
    resolutionLabel: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close), tint = Color.White)
        }
        IconButton(onClick = onHelp) {
            Icon(Icons.Outlined.HelpOutline, contentDescription = stringResource(R.string.editor_help), tint = Color.White)
        }
        Spacer(Modifier.weight(1f))
        Surface(
            color = Color(0xFF2A2A32),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                text = resolutionLabel.ifBlank { "1080P" },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.width(8.dp))
        Surface(
            onClick = onExport,
            color = Color(0xFF22D3EE),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                text = stringResource(R.string.editor_export),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = Color(0xFF04222A),
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun TransportBar(
    isPlaying: Boolean,
    playheadUs: Long,
    durationUs: Long,
    canUndo: Boolean,
    canRedo: Boolean,
    dirty: Boolean,
    saving: Boolean,
    onPlay: () -> Unit,
    onSplit: () -> Unit,
    onOverlay: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF111114))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPlay) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(if (isPlaying) R.string.editor_pause else R.string.editor_play),
                tint = Color.White,
            )
        }
        Text(
            "${formatTimecode(playheadUs)} / ${formatTimecode(durationUs)}",
            color = Color(0xFFB0B0BC),
            fontSize = 11.sp,
            modifier = Modifier.padding(end = 8.dp),
        )
        TransportIcon(Icons.Outlined.ContentCut, stringResource(R.string.editor_split), onSplit)
        TransportIcon(Icons.Outlined.Layers, stringResource(R.string.tool_overlay), onOverlay)
        TransportIcon(Icons.Outlined.Undo, stringResource(R.string.action_undo), onUndo, enabled = canUndo)
        TransportIcon(Icons.Outlined.Redo, stringResource(R.string.action_redo), onRedo, enabled = canRedo)
        Spacer(Modifier.weight(1f))
        if (saving || dirty) {
            Text(
                stringResource(if (saving) R.string.editor_saving else R.string.action_save),
                color = Color(0xFF8A8A9A),
                fontSize = 10.sp,
            )
        }
        IconButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.editor_add_media), tint = Color(0xFF22D3EE))
        }
    }
}

@Composable
private fun TransportIcon(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = label, tint = if (enabled) Color.White else Color(0xFF55555F))
    }
}

@Composable
private fun ClipContextBar(
    reversed: Boolean,
    opacity: Float,
    stabilized: Boolean,
    onSplit: () -> Unit,
    onStabilize: () -> Unit,
    onOpacity: () -> Unit,
    onOpacityChange: (Float) -> Unit,
    onReverse: () -> Unit,
    onFreeze: () -> Unit,
    onVoice: () -> Unit,
    onSpeed: () -> Unit,
    onCutout: () -> Unit,
    onAi: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF16161C))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContextAction(Icons.Outlined.ContentCut, stringResource(R.string.tool_split), onSplit)
        ContextAction(
            Icons.Outlined.Videocam,
            stringResource(R.string.tool_stabilize),
            onStabilize,
            highlighted = stabilized,
        )
        ContextAction(
            Icons.Outlined.Tune,
            "${stringResource(R.string.tool_opacity)} ${(opacity * 100).toInt()}%",
            onOpacity,
        )
        Slider(
            value = opacity,
            onValueChange = onOpacityChange,
            valueRange = 0f..1f,
            modifier = Modifier.width(110.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF22D3EE),
                activeTrackColor = Color(0xFF22D3EE),
                inactiveTrackColor = Color(0xFF3A3A44),
            ),
        )
        ContextAction(
            Icons.Outlined.SwapHoriz,
            stringResource(R.string.tool_reverse),
            onReverse,
            highlighted = reversed,
        )
        ContextAction(Icons.Outlined.Crop, stringResource(R.string.tool_freeze_frame), onFreeze)
        ContextAction(Icons.Outlined.GraphicEq, stringResource(R.string.tool_voice_effects), onVoice)
        ContextAction(Icons.Outlined.SwapHoriz, stringResource(R.string.tool_speed), onSpeed)
        ContextAction(Icons.Outlined.Person, stringResource(R.string.tool_cutout), onCutout)
        ContextAction(Icons.Outlined.AutoAwesome, stringResource(R.string.ai_title), onAi)
    }
}

@Composable
private fun ContextAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (highlighted) Color(0xFF22D3EE) else Color(0xFFD0D0DC),
            modifier = Modifier.size(20.dp),
        )
        Text(label, color = Color(0xFFB0B0BC), fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun StudioDock(onTool: (EditorSheet) -> Unit) {
    val tools = listOf(
        ToolEntry(EditorSheet.MEDIA, Icons.Outlined.Movie, R.string.editor_add_media),
        ToolEntry(EditorSheet.AUDIO, Icons.Outlined.GraphicEq, R.string.tool_audio),
        ToolEntry(EditorSheet.OVERLAY, Icons.Outlined.Layers, R.string.tool_overlay),
        ToolEntry(EditorSheet.EFFECTS, Icons.Outlined.Star, R.string.tool_effects),
        ToolEntry(EditorSheet.FILTERS, Icons.Outlined.Colorize, R.string.tool_filters),
        ToolEntry(EditorSheet.TEXT, Icons.Outlined.TextFields, R.string.tool_text),
        ToolEntry(EditorSheet.STICKER, Icons.Outlined.EmojiEmotions, R.string.tool_sticker),
        ToolEntry(EditorSheet.CAPTIONS, Icons.Outlined.ClosedCaption, R.string.captions_title),
        ToolEntry(EditorSheet.CHROMA, Icons.Outlined.Videocam, R.string.tool_cutout),
        ToolEntry(EditorSheet.ADJUST, Icons.Outlined.Tune, R.string.tool_adjust),
        ToolEntry(EditorSheet.TRANSITION, Icons.Outlined.AutoFixHigh, R.string.tool_transition),
        ToolEntry(EditorSheet.CLIP, Icons.Outlined.ContentCut, R.string.tool_edit),
        ToolEntry(EditorSheet.SPEED, Icons.Outlined.SwapHoriz, R.string.tool_speed),
        ToolEntry(EditorSheet.TRANSFORM, Icons.Outlined.OpenWith, R.string.tool_transform),
        ToolEntry(EditorSheet.MASK, Icons.Outlined.CenterFocusWeak, R.string.tool_mask),
        ToolEntry(EditorSheet.KEYFRAME, Icons.Outlined.Key, R.string.tool_keyframe_add),
        ToolEntry(EditorSheet.CANVAS, Icons.Outlined.Fullscreen, R.string.tool_canvas),
        ToolEntry(EditorSheet.AI, Icons.Outlined.AutoAwesome, R.string.ai_title),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF111114))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tools.forEach { tool ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onTool(tool.sheet) },
            ) {
                Icon(
                    tool.icon,
                    contentDescription = stringResource(tool.labelRes),
                    tint = Color(0xFFE8E8F0),
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    stringResource(tool.labelRes),
                    color = Color(0xFFB0B0BC),
                    fontSize = 10.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

private data class ToolEntry(
    val sheet: EditorSheet,
    val icon: ImageVector,
    val labelRes: Int,
)
