package com.vitacut.feature.home

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vitacut.core.common.time.formatDuration
import com.vitacut.core.designsystem.R
import com.vitacut.core.designsystem.components.VitaAsyncImage
import com.vitacut.core.designsystem.components.VitaButton
import com.vitacut.core.designsystem.components.VitaChipItem
import com.vitacut.core.designsystem.components.VitaChipRow
import com.vitacut.core.designsystem.components.VitaLoading
import com.vitacut.core.designsystem.strings.VitaStrings
import com.vitacut.core.model.AspectRatio
import com.vitacut.domain.repository.ProjectSummary

/**
 * CapCut-style studio home: shortcut row, large New project CTA, local drafts, bottom hub.
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
    var tab by remember { mutableStateOf(HomeLibraryTab.LOCAL) }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = true,
                    onClick = {},
                    icon = { Icon(Icons.Outlined.ContentCut, contentDescription = null) },
                    label = { Text(stringResource(R.string.hub_edit)) },
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onOpenTemplates,
                    icon = { Icon(Icons.Outlined.Movie, contentDescription = null) },
                    label = { Text(stringResource(R.string.home_templates)) },
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onOpenSettings,
                    icon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                    label = { Text(stringResource(R.string.hub_me)) },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            HomeTopBar(
                onHelp = {
                    Toast.makeText(
                        context,
                        context.getString(R.string.app_tagline),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                onOpenSettings = onOpenSettings,
            )
            ShortcutRow(
                onCamera = { viewModel.showNewProjectSheet(true) },
                onRetouch = { viewModel.showNewProjectSheet(true) },
                onCaptions = { viewModel.showNewProjectSheet(true) },
                onTemplates = onOpenTemplates,
            )
            NewProjectHero(onClick = { viewModel.showNewProjectSheet(true) })
            LibraryHeader(
                tab = tab,
                onTab = { next ->
                    tab = next
                    if (next == HomeLibraryTab.CLOUD) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.home_cloud_soon),
                            Toast.LENGTH_SHORT,
                        ).show()
                        tab = HomeLibraryTab.LOCAL
                    }
                },
                query = state.query,
                onQuery = viewModel::onQueryChange,
            )
            when {
                state.isLoading -> VitaLoading()
                state.visibleProjects.isEmpty() -> EmptyLibrary(
                    onCreate = { viewModel.showNewProjectSheet(true) },
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 156.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
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

    Box {
        DropdownMenu(expanded = menuFor != null, onDismissRequest = { menuFor = null }) {
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

    state.actionErrorKey?.let { key ->
        val errorContext = LocalContext.current
        LaunchedEffect(key) {
            Toast.makeText(
                errorContext,
                VitaStrings.localized(errorContext, key),
                Toast.LENGTH_SHORT,
            ).show()
            viewModel.consumeError()
        }
    }

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

private enum class HomeLibraryTab { LOCAL, CLOUD }

@Composable
private fun HomeTopBar(onHelp: () -> Unit, onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                text = stringResource(R.string.app_edition),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onHelp) {
            Icon(Icons.Outlined.HelpOutline, contentDescription = stringResource(R.string.editor_help))
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.home_settings))
        }
    }
}

@Composable
private fun ShortcutRow(
    onCamera: () -> Unit,
    onRetouch: () -> Unit,
    onCaptions: () -> Unit,
    onTemplates: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Shortcut(Icons.Outlined.PhotoCamera, stringResource(R.string.home_camera), onCamera)
        Shortcut(Icons.Outlined.AutoFixHigh, stringResource(R.string.home_retouch), onRetouch)
        Shortcut(Icons.Outlined.ClosedCaption, stringResource(R.string.home_captions_shortcut), onCaptions)
        Shortcut(Icons.Outlined.Style, stringResource(R.string.home_prompter), onTemplates)
    }
}

@Composable
private fun Shortcut(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurface)
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 6.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NewProjectHero(onClick: () -> Unit) {
    val brush = Brush.horizontalGradient(
        listOf(Color(0xFF3B82F6), Color(0xFF22D3EE)),
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(92.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(brush)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White)
            }
            Text(
                stringResource(R.string.home_new_project),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun LibraryHeader(
    tab: HomeLibraryTab,
    onTab: (HomeLibraryTab) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.home_local),
            fontWeight = if (tab == HomeLibraryTab.LOCAL) FontWeight.Bold else FontWeight.Normal,
            color = if (tab == HomeLibraryTab.LOCAL) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.clickable { onTab(HomeLibraryTab.LOCAL) },
        )
        Spacer(Modifier.width(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { onTab(HomeLibraryTab.CLOUD) },
        ) {
            Icon(
                Icons.Outlined.Cloud,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
            Text(
                stringResource(R.string.home_cloud),
                modifier = Modifier.padding(start = 4.dp),
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Spacer(Modifier.weight(1f))
    }
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        placeholder = { Text(stringResource(R.string.home_search_hint)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
    )
}

@Composable
private fun EmptyLibrary(onCreate: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.home_projects_hint),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        VitaButton(text = stringResource(R.string.home_new_project), onClick = onCreate)
    }
}

@Composable
private fun NewProjectSheet(
    onDismiss: () -> Unit,
    onCreate: (String, AspectRatio) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var aspect by remember { mutableStateOf(AspectRatio.RATIO_9_16) }
    val aspects = remember {
        listOf(
            AspectRatio.RATIO_9_16 to "9:16",
            AspectRatio.RATIO_16_9 to "16:9",
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
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column {
            Box {
                VitaAsyncImage(
                    model = summary.thumbnailPath,
                    contentDescription = summary.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(summary.canvasAspect.coerceIn(0.45f, 1.8f)),
                )
                IconButton(
                    onClick = onMenu,
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.home_project_menu),
                        tint = Color.White,
                    )
                }
            }
            Column(modifier = Modifier.padding(10.dp)) {
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
}
