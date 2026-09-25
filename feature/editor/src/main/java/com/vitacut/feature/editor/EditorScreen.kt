package com.vitacut.feature.editor

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Caption
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material.icons.outlined.Redo
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.vitacut.core.designsystem.components.VitaIconButton
import com.vitacut.core.designsystem.components.VitaLoading
import com.vitacut.core.designsystem.components.VitaTopBar
import com.vitacut.core.designsystem.strings.VitaStrings
import com.vitacut.core.media.record.RecorderState
import com.vitacut.feature.editor.timeline.TimelinePanel
import com.vitacut.feature.editor.tools.ToolSheetHost

/**
 * The editor: preview + transport + multi-track timeline + tool rail. All editing flows through
 * [EditorViewModel]; the preview renders through the shared export pipeline via
 * [com.vitacut.feature.editor.preview.PreviewController].
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

    // One-shot messages.
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
        VitaLoading(
            modifier = modifier,
            label = stringResource(R.string.editor_loading),
        )
        return
    }
    if (uiState.loadErrorKey != null || project == null) {
        VitaErrorState(
            title = stringResource(R.string.error_project_unreadable),
            message = VitaStrings.localized(
                context,
                uiState.loadErrorKey ?: "error_project_unreadable",
            ),
            modifier = modifier,
            retryLabel = stringResource(R.string.action_back),
            onRetry = onExit,
        )
        return
    }
    val currentProject = project!!

    // Refresh the paused preview whenever the document changes (effects, grading, canvas…).
    LaunchedEffect(currentProject, uiState.playheadUs, uiState.isPlaying) {
        if (!uiState.isPlaying) {
            controller.invalidateEffects(currentProject, uiState.playheadUs)
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            VitaTopBar(
                title = currentProject.name.ifBlank { stringResource(R.string.editor_title) },
                onBack = { viewModel.requestBack(onExit) },
                backContentDescription = stringResource(R.string.action_back),
                actions = {
                    VitaIconButton(
                        icon = Icons.Outlined.Undo,
                        contentDescription = stringResource(R.string.action_undo),
                        onClick = viewModel::undo,
                        enabled = uiState.canUndo,
                    )
                    VitaIconButton(
                        icon = Icons.Outlined.Redo,
                        contentDescription = stringResource(R.string.action_redo),
                        onClick = viewModel::redo,
                        enabled = uiState.canRedo,
                    )
                    VitaIconButton(
                        icon = Icons.Outlined.Upload,
                        contentDescription = stringResource(R.string.editor_export),
                        onClick = { onExport(currentProject.id.value) },
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Preview surface.
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
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(
                                currentProject.canvas.aspectRatio.ratio.coerceIn(0.3f, 3.5f),
                            ),
                    )

                    // Busy overlay.
                    uiState.busyKey?.let { key ->
                        Box(
                            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                Text(
                                    text = if (uiState.busyPercent >= 0) {
                                        VitaStrings.localized(context, key, uiState.busyPercent)
                                            .takeIf { it.contains("%") }
                                            ?: "${VitaStrings.localized(context, key)} ${uiState.busyPercent}%"
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

                    // Recording HUD.
                    if (uiState.recorderState != RecorderState.IDLE) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 12.dp)
                                .background(Color.Black.copy(alpha = 0.7f))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Filled.Mic, contentDescription = null, tint = Color.Red, modifier = Modifier.size(16.dp))
                            Text(
                                stringResource(R.string.editor_recording),
                                color = Color.White,
                                fontSize = 12.sp,
                            )
                            Text(
                                formatTimecode(uiState.recordDurationMs * 1000L, showMillis = false),
                                color = Color.White,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }

                // Transport bar.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(onClick = viewModel::togglePlay) {
                        Icon(
                            if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(
                                if (uiState.isPlaying) R.string.editor_pause else R.string.editor_play,
                            ),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        "${formatTimecode(uiState.playheadUs)} / ${formatTimecode(uiState.durationUs)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(modifier = Modifier.weight(1f))
                    if (uiState.saving || uiState.dirty) {
                        Text(
                            stringResource(if (uiState.saving) R.string.editor_saving else R.string.action_save),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { viewModel.openSheet(EditorSheet.MEDIA) },
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.editor_add_media),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                // Timeline.
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
                        .height(220.dp),
                )

                // Tool rail.
                ToolRail(onTool = viewModel::openSheet)
            }
        }
    }

    // Tool sheets.
    ToolSheetHost(
        sheet = uiState.activeSheet,
        viewModel = viewModel,
        project = currentProject,
        onDismiss = { viewModel.openSheet(null) },
    )

    // Save/discard dialog.
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
private fun ToolRail(
    onTool: (EditorSheet) -> Unit,
) {
    val tools = listOf(
        ToolEntry(EditorSheet.MEDIA, Icons.Outlined.Movie, R.string.editor_add_media),
        ToolEntry(EditorSheet.CLIP, Icons.Outlined.ContentCut, R.string.tool_edit),
        ToolEntry(EditorSheet.TRANSFORM, Icons.Outlined.OpenWith, R.string.tool_transform),
        ToolEntry(EditorSheet.SPEED, Icons.Outlined.SwapHoriz, R.string.tool_speed),
        ToolEntry(EditorSheet.AUDIO, Icons.Outlined.GraphicEq, R.string.tool_audio),
        ToolEntry(EditorSheet.TEXT, Icons.Outlined.TextFields, R.string.tool_text),
        ToolEntry(EditorSheet.STICKER, Icons.Outlined.EmojiEmotions, R.string.tool_sticker),
        ToolEntry(EditorSheet.OVERLAY, Icons.Outlined.Layers, R.string.tool_overlay),
        ToolEntry(EditorSheet.TRANSITION, Icons.Outlined.AutoFixHigh, R.string.tool_transition),
        ToolEntry(EditorSheet.EFFECTS, Icons.Outlined.Star, R.string.tool_effects),
        ToolEntry(EditorSheet.FILTERS, Icons.Outlined.Colorize, R.string.tool_filters),
        ToolEntry(EditorSheet.ADJUST, Icons.Outlined.Tune, R.string.tool_adjust),
        ToolEntry(EditorSheet.MASK, Icons.Outlined.CenterFocusWeak, R.string.tool_mask),
        ToolEntry(EditorSheet.CHROMA, Icons.Outlined.Videocam, R.string.tool_chroma_key),
        ToolEntry(EditorSheet.CANVAS, Icons.Outlined.Crop, R.string.tool_canvas),
        ToolEntry(EditorSheet.CAPTIONS, Icons.Outlined.Caption, R.string.captions_title),
        ToolEntry(EditorSheet.AI, Icons.Outlined.AutoAwesome, R.string.ai_title),
        ToolEntry(EditorSheet.KEYFRAME, Icons.Outlined.Key, R.string.tool_keyframe_add),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tools.forEach { tool ->
            Column(
                modifier = Modifier
                    .clickable { onTool(tool.sheet) }
                    .padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    tool.icon,
                    contentDescription = stringResource(tool.labelRes),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    stringResource(tool.labelRes),
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
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
