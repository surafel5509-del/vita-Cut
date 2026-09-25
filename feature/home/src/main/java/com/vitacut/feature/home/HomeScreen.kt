package com.vitacut.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vitacut.core.common.time.formatDuration
import com.vitacut.core.designsystem.R
import com.vitacut.core.designsystem.components.VitaAsyncImage
import com.vitacut.core.designsystem.components.VitaButton
import com.vitacut.core.designsystem.components.VitaCard
import com.vitacut.core.designsystem.components.VitaChipItem
import com.vitacut.core.designsystem.components.VitaChipRow
import com.vitacut.core.designsystem.components.VitaEmptyState
import com.vitacut.core.designsystem.components.VitaIconButton
import com.vitacut.core.designsystem.components.VitaLoading
import com.vitacut.core.designsystem.components.VitaTopBar
import com.vitacut.core.designsystem.strings.VitaStrings
import com.vitacut.core.model.AspectRatio
import com.vitacut.domain.repository.ProjectSummary

/**
 * Home dashboard: searchable project grid, new-project sheet, per-project actions and the
 * crash-recovery dialog (spec: autosave + recovery prompt on launch).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenProject: (String) -> Unit,
    onOpenTemplates: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var menuFor by remember { mutableStateOf<ProjectSummary?>(null) }
    var renaming by remember { mutableStateOf<ProjectSummary?>(null) }
    var deleting by remember { mutableStateOf<ProjectSummary?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            VitaTopBar(
                title = stringResource(R.string.home_title),
                actions = {
                    VitaIconButton(
                        icon = Icons.Outlined.GridView,
                        contentDescription = stringResource(R.string.home_templates),
                        onClick = onOpenTemplates,
                    )
                    VitaIconButton(
                        icon = Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.home_settings),
                        onClick = onOpenSettings,
                    )
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.showNewProjectSheet(true) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.home_new_project)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.home_search_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
            )

            when {
                state.isLoading -> VitaLoading()
                state.visibleProjects.isEmpty() -> VitaEmptyState(
                    icon = Icons.Outlined.GridView,
                    iconContentDescription = null,
                    title = stringResource(R.string.home_empty_title),
                    message = stringResource(R.string.home_empty_message),
                    actionLabel = stringResource(R.string.home_new_project),
                    onAction = { viewModel.showNewProjectSheet(true) },
                )

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 168.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.visibleProjects, key = { it.projectId }) { summary ->
                        ProjectCard(
                            summary = summary,
                            onClick = { onOpenProject(summary.projectId) },
                            onMenu = { menuFor = summary },
                        )
                    }
                }
            }
        }
    }

    // Project actions menu.
    Box {
        DropdownMenu(
            expanded = menuFor != null,
            onDismissRequest = { menuFor = null },
        ) {
            val target = menuFor
            if (target != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_rename)) },
                    onClick = { renaming = target; menuFor = null },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_duplicate)) },
                    onClick = {
                        viewModel.duplicate(target) { name ->
                            context.getString(R.string.home_duplicate_name, name)
                        }
                        menuFor = null
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.action_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = { deleting = target; menuFor = null },
                )
            }
        }
    }

    // Rename dialog.
    renaming?.let { target ->
        var name by remember(target.projectId) { mutableStateOf(target.name) }
        com.vitacut.core.designsystem.components.VitaDialog(
            title = stringResource(R.string.action_rename),
            onDismiss = { renaming = null },
            confirmText = stringResource(R.string.action_save),
            onConfirm = {
                viewModel.rename(target.projectId, name)
                renaming = null
            },
            dismissText = stringResource(R.string.action_cancel),
            content = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.home_project_name_hint)) },
                    singleLine = true,
                )
            },
        )
    }

    // Delete confirmation.
    deleting?.let { target ->
        com.vitacut.core.designsystem.components.VitaConfirmDialog(
            title = stringResource(R.string.home_delete_title),
            message = stringResource(R.string.home_delete_message, target.name),
            confirmText = stringResource(R.string.action_delete),
            dismissText = stringResource(R.string.action_cancel),
            danger = true,
            onConfirm = {
                viewModel.delete(target.projectId)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }

    // Crash recovery dialog.
    state.recoverable?.let { recoverable ->
        com.vitacut.core.designsystem.components.VitaConfirmDialog(
            title = stringResource(R.string.home_recovery_title),
            message = stringResource(R.string.home_recovery_message, recoverable.name),
            confirmText = stringResource(R.string.home_recovery_keep),
            dismissText = stringResource(R.string.home_recovery_discard),
            onConfirm = { viewModel.resolveRecovery(true) { onOpenProject(it) } },
            onDismiss = { viewModel.resolveRecovery(false) { onOpenProject(it) } },
        )
    }

    // Error surface (Toast — non-blocking, self-dismissing).
    state.actionErrorKey?.let { key ->
        val errorContext = LocalContext.current
        androidx.compose.runtime.LaunchedEffect(key) {
            android.widget.Toast.makeText(
                errorContext,
                VitaStrings.localized(errorContext, key),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            viewModel.consumeError()
        }
    }

    // New project sheet.
    if (state.showNewProjectSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewModel.showNewProjectSheet(false) },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            NewProjectSheet(
                onDismiss = { viewModel.showNewProjectSheet(false) },
                onCreate = { name, aspect -> viewModel.createProject(name, aspect, onOpenProject) },
            )
        }
    }
}

@Composable
private fun NewProjectSheet(
    onDismiss: () -> Unit,
    onCreate: (String, AspectRatio) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var aspect by remember { mutableStateOf(AspectRatio.RATIO_16_9) }
    val aspects = remember {
        listOf(
            AspectRatio.RATIO_16_9 to "16:9",
            AspectRatio.RATIO_9_16 to "9:16",
            AspectRatio.RATIO_1_1 to "1:1",
            AspectRatio.RATIO_4_5 to "4:5",
            AspectRatio.RATIO_3_4 to "3:4",
            AspectRatio.RATIO_21_9 to "21:9",
        )
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Text(stringResource(R.string.home_new_project_title), style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            label = { Text(stringResource(R.string.home_project_name_hint)) },
            singleLine = true,
        )
        Text(
            stringResource(R.string.home_aspect_ratio),
            modifier = Modifier.padding(top = 20.dp),
            style = MaterialTheme.typography.labelMedium,
        )
        VitaChipRow(
            modifier = Modifier.padding(horizontal = 0.dp),
            chips = aspects.map { (ratio, label) ->
                VitaChipItem(ratio.name, label, selected = ratio == aspect)
            },
            onChipClick = { id -> aspect = aspects.first { it.first.name == id }.first },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            com.vitacut.core.designsystem.components.VitaTextButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
            )
            VitaButton(
                text = stringResource(R.string.home_new_project),
                onClick = { onCreate(name, aspect) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ProjectCard(
    summary: ProjectSummary,
    onClick: () -> Unit,
    onMenu: () -> Unit,
) {
    VitaCard(onClick = onClick) {
        Box {
            VitaAsyncImage(
                model = summary.thumbnailPath,
                contentDescription = summary.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(summary.canvasAspect.coerceIn(0.3f, 3f)),
            )
            IconButton(
                onClick = onMenu,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.home_project_menu),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                summary.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    R.string.home_duration_format,
                    formatDuration(summary.durationUs),
                    summary.resolutionLabel,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
