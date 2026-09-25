package com.vitacut.feature.templates

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.vitacut.core.designsystem.components.VitaButton
import com.vitacut.core.designsystem.components.VitaChipItem
import com.vitacut.core.designsystem.components.VitaChipRow
import com.vitacut.core.designsystem.components.VitaDialog
import com.vitacut.core.designsystem.components.VitaEmptyState
import com.vitacut.core.designsystem.components.VitaLoading
import com.vitacut.core.designsystem.components.VitaTopBar
import com.vitacut.core.designsystem.strings.VitaStrings
import com.vitacut.core.model.TemplateCategory
import com.vitacut.domain.repository.TemplateMeta

/** Template gallery: bundled + user templates, favorite/delete, and "use" → new project. */
@Composable
fun TemplatesScreen(
    onBack: () -> Unit,
    onProjectCreated: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TemplatesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var using by remember { mutableStateOf<TemplateMeta?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            VitaTopBar(
                title = stringResource(R.string.templates_title),
                onBack = onBack,
                backContentDescription = stringResource(R.string.action_back),
            )
        },
    ) { padding ->
        when {
            state.isLoading -> VitaLoading(Modifier.padding(padding))
            state.templates.isEmpty() -> VitaEmptyState(
                icon = Icons.Outlined.Movie,
                iconContentDescription = null,
                title = stringResource(R.string.templates_empty),
                message = stringResource(R.string.templates_save_current),
                modifier = Modifier.padding(padding),
            )

            else -> Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                VitaChipRow(
                    chips = buildList {
                        add(VitaChipItem("all", stringResource(R.string.templates_category_all), state.category == null))
                        TemplateCategory.entries.forEach { category ->
                            add(
                                VitaChipItem(
                                    category.name,
                                    category.name.lowercase().replace('_', ' '),
                                    state.category == category.name,
                                ),
                            )
                        }
                    },
                    onChipClick = { id ->
                        viewModel.setCategory(id.takeIf { it != "all" })
                    },
                )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                modifier = Modifier.weight(1f).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.templates, key = { it.template.id.value }) { meta ->
                    TemplateCard(
                        meta = meta,
                        onClick = { using = meta },
                        onToggleFavorite = {
                            viewModel.toggleFavorite(meta.template.id.value, !meta.isFavorite)
                        },
                        onDelete = if (meta.template.id.value.startsWith("user-")) {
                            { viewModel.deleteTemplate(meta.template.id.value) }
                        } else null,
                    )
                }
            }
            }
        }
    }

    using?.let { meta ->
        var name by remember(meta.template.id) { mutableStateOf("") }
        VitaDialog(
            title = VitaStrings.localized(context, meta.template.nameKey),
            onDismiss = { using = null },
            confirmText = stringResource(R.string.templates_use),
            onConfirm = {
                val projectName = name.ifBlank {
                    VitaStrings.localized(context, meta.template.nameKey)
                }
                viewModel.useTemplate(meta.template, projectName) { projectId ->
                    using = null
                    onProjectCreated(projectId)
                }
            },
            dismissText = stringResource(R.string.action_cancel),
            content = {
                Column {
                    Text(
                        meta.template.descriptionKey?.let { VitaStrings.localized(context, it) }.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        label = { Text(stringResource(R.string.home_project_name_hint)) },
                        singleLine = true,
                    )
                    Text(
                        "${formatDuration(meta.template.totalDurationUs)} · " +
                            "${meta.template.canvas.width}×${meta.template.canvas.height} · " +
                            "${meta.template.slots.count { it.kind.name == "MEDIA" }} clips",
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
    }

    state.errorKey?.let { key ->
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
}

@Composable
private fun TemplateCard(
    meta: TemplateMeta,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val context = LocalContext.current
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(meta.template.canvas.aspect.coerceIn(0.3f, 3f))
                    .padding(0.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Structure preview: one stripe per slot — an original, media-free thumbnail.
                Row(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    meta.template.slots.forEach { slot ->
                        Surface(
                            modifier = Modifier.weight(slot.durationUs.toFloat()).fillMaxSize(),
                            shape = MaterialTheme.shapes.extraSmall,
                            color = if (slot.kind.name == "TEXT") {
                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                            },
                        ) {}
                    }
                }
                Row(modifier = Modifier.align(Alignment.TopEnd)) {
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            if (meta.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = stringResource(R.string.templates_favorite),
                            tint = if (meta.isFavorite) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    if (onDelete != null) {
                        IconButton(onClick = onDelete) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                Text(
                    VitaStrings.localized(context, meta.template.nameKey),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${meta.template.category.name.lowercase()} · ${formatDuration(meta.template.totalDurationUs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
