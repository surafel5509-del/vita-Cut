package com.vitacut.feature.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vitacut.core.common.device.PerformanceMode
import com.vitacut.core.datastore.ThemeMode
import com.vitacut.core.designsystem.R
import com.vitacut.core.designsystem.components.VitaChipItem
import com.vitacut.core.designsystem.components.VitaChipRow
import com.vitacut.core.designsystem.components.VitaOutlinedButton
import com.vitacut.core.designsystem.components.VitaTextButton
import com.vitacut.core.designsystem.components.VitaTopBar
import com.vitacut.core.model.ExportFrameRate
import com.vitacut.core.model.ExportResolution
import com.vitacut.core.model.ExportSettings
import com.vitacut.core.model.ExportVideoCodec
import com.vitacut.core.model.ExportBitrateMode

/**
 * Settings: appearance, language, performance mode (with the device recommendation shown),
 * autosave, export defaults, SAF export destination, storage cleanup, privacy & about.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val cleared by viewModel.clearedCache.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val settings = state.settings

    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.setExportLocation(uri.toString())
        }
    }

    cleared?.let { bytes ->
        androidx.compose.runtime.LaunchedEffect(bytes) {
            val label = if (bytes > 0) {
                android.text.format.Formatter.formatShortFileSize(context, bytes)
            } else "0 B"
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.settings_cache_cleared, label),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            viewModel.consumeClearedCache()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            VitaTopBar(
                title = stringResource(R.string.settings_title),
                onBack = onBack,
                backContentDescription = stringResource(R.string.action_back),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(stringResource(R.string.settings_appearance))
            LabeledChipRow(
                label = stringResource(R.string.settings_theme),
                items = listOf(
                    VitaChipItem(ThemeMode.DARK.name, stringResource(R.string.settings_theme_dark), settings.themeMode == ThemeMode.DARK),
                    VitaChipItem(ThemeMode.LIGHT.name, stringResource(R.string.settings_theme_light), settings.themeMode == ThemeMode.LIGHT),
                    VitaChipItem(ThemeMode.SYSTEM.name, stringResource(R.string.settings_theme_system), settings.themeMode == ThemeMode.SYSTEM),
                ),
                onClick = { viewModel.setThemeMode(ThemeMode.valueOf(it)) },
            )
            LabeledChipRow(
                label = stringResource(R.string.settings_language),
                items = buildList {
                    add(VitaChipItem("", stringResource(R.string.settings_language_system), settings.languageTag.isEmpty()))
                    state.supportedLanguages.forEach { tag ->
                        add(VitaChipItem(tag, localeDisplayName(tag), settings.languageTag == tag))
                    }
                },
                onClick = { tag ->
                    // The app module observes this preference and applies per-app locales
                    // (AppCompatDelegate.setApplicationLocales) with a backport below API 33.
                    viewModel.setLanguage(tag)
                },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionHeader(stringResource(R.string.settings_performance))
            LabeledChipRow(
                label = stringResource(R.string.settings_performance_mode) +
                    " · " + stringResource(R.string.settings_performance_auto) +
                    ": ${state.recommendedMode.name.lowercase()}",
                items = listOf(
                    VitaChipItem("", stringResource(R.string.settings_performance_auto), settings.performanceModeOverride.isEmpty()),
                    VitaChipItem(PerformanceMode.HIGH_QUALITY.name, stringResource(R.string.settings_performance_high), settings.performanceModeOverride == PerformanceMode.HIGH_QUALITY.name),
                    VitaChipItem(PerformanceMode.BALANCED.name, stringResource(R.string.settings_performance_balanced), settings.performanceModeOverride == PerformanceMode.BALANCED.name),
                    VitaChipItem(PerformanceMode.PERFORMANCE.name, stringResource(R.string.settings_performance_battery), settings.performanceModeOverride == PerformanceMode.PERFORMANCE.name),
                ),
                onClick = { value ->
                    viewModel.setPerformanceMode(
                        value.takeIf { it.isNotEmpty() }?.let { PerformanceMode.valueOf(it) },
                    )
                },
            )
            SwitchRow(
                label = stringResource(R.string.settings_hardware_acceleration),
                checked = settings.hardwareAcceleration,
                onCheckedChange = viewModel::setHardwareAcceleration,
            )
            SwitchRow(
                label = stringResource(R.string.settings_proxy_media),
                checked = settings.proxyMediaEnabled,
                onCheckedChange = viewModel::setProxyMedia,
            )
            SwitchRow(
                label = stringResource(R.string.settings_reduced_motion),
                checked = settings.reducedMotion,
                onCheckedChange = viewModel::setReducedMotion,
            )
            SwitchRow(
                label = stringResource(R.string.settings_notifications),
                checked = settings.notificationsEnabled,
                onCheckedChange = viewModel::setNotifications,
            )
            SwitchRow(
                label = stringResource(R.string.settings_haptics),
                checked = settings.hapticsEnabled,
                onCheckedChange = viewModel::setHaptics,
            )
            SwitchRow(
                label = stringResource(R.string.settings_autosave),
                checked = settings.autoSaveEnabled,
                onCheckedChange = { viewModel.setAutoSave(it, null) },
            )
            if (settings.autoSaveEnabled) {
                LabeledChipRow(
                    label = stringResource(R.string.settings_autosave_interval, settings.autoSaveIntervalSeconds),
                    items = listOf(10, 20, 60).map { seconds ->
                        VitaChipItem(seconds.toString(), "${seconds}s", settings.autoSaveIntervalSeconds == seconds)
                    },
                    onClick = { viewModel.setAutoSave(true, it.toIntOrNull()) },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionHeader(stringResource(R.string.settings_export_defaults))
            val export = settings.defaultExportSettings
            LabeledChipRow(
                label = stringResource(R.string.export_resolution),
                items = ExportResolution.entries.map {
                    VitaChipItem(it.name, it.label, export.resolution == it)
                },
                onClick = { id ->
                    viewModel.setDefaultExportSettings(
                        export.copy(resolution = ExportResolution.valueOf(id)),
                    )
                },
            )
            LabeledChipRow(
                label = stringResource(R.string.export_frame_rate),
                items = ExportFrameRate.entries.map {
                    VitaChipItem(it.name, it.label, export.frameRate == it)
                },
                onClick = { id ->
                    viewModel.setDefaultExportSettings(
                        export.copy(frameRate = ExportFrameRate.valueOf(id)),
                    )
                },
            )
            LabeledChipRow(
                label = stringResource(R.string.export_codec),
                items = ExportVideoCodec.entries.map {
                    VitaChipItem(it.name, if (it == ExportVideoCodec.HEVC) "H.265/HEVC" else "H.264", export.videoCodec == it)
                },
                onClick = { id ->
                    viewModel.setDefaultExportSettings(
                        export.copy(videoCodec = ExportVideoCodec.valueOf(id)),
                    )
                },
            )
            LabeledChipRow(
                label = stringResource(R.string.export_bitrate),
                items = listOf(
                    VitaChipItem(ExportBitrateMode.LOW.name, stringResource(R.string.export_bitrate_low), export.bitrateMode == ExportBitrateMode.LOW),
                    VitaChipItem(ExportBitrateMode.MEDIUM.name, stringResource(R.string.export_bitrate_medium), export.bitrateMode == ExportBitrateMode.MEDIUM),
                    VitaChipItem(ExportBitrateMode.HIGH.name, stringResource(R.string.export_bitrate_high), export.bitrateMode == ExportBitrateMode.HIGH),
                    VitaChipItem(ExportBitrateMode.CUSTOM.name, stringResource(R.string.export_bitrate_custom), export.bitrateMode == ExportBitrateMode.CUSTOM),
                ),
                onClick = { id ->
                    viewModel.setDefaultExportSettings(
                        export.copy(bitrateMode = ExportBitrateMode.valueOf(id)),
                    )
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.export_destination), style = MaterialTheme.typography.labelMedium)
                    Text(
                        if (settings.exportLocationUri.isNullOrBlank()) {
                            stringResource(R.string.export_destination_default)
                        } else {
                            stringResource(R.string.export_destination_custom)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                VitaTextButton(
                    text = stringResource(R.string.export_destination_custom),
                    onClick = { treePicker.launch(null) },
                )
                if (!settings.exportLocationUri.isNullOrBlank()) {
                    VitaTextButton(
                        text = stringResource(R.string.action_reset),
                        onClick = { viewModel.setExportLocation(null) },
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionHeader(stringResource(R.string.settings_storage))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                VitaOutlinedButton(
                    text = stringResource(R.string.settings_clear_cache),
                    onClick = viewModel::clearCache,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionHeader(stringResource(R.string.settings_about))
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.settings_privacy), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.settings_privacy_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    stringResource(
                        R.string.settings_version,
                        runCatching {
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName
                        }.getOrNull() ?: "1.0",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 24.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun LabeledChipRow(
    label: String,
    items: List<VitaChipItem>,
    onClick: (String) -> Unit,
) {
    Column {
        Text(
            label,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
            style = MaterialTheme.typography.labelMedium,
        )
        VitaChipRow(chips = items, onChipClick = onClick)
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun localeDisplayName(tag: String): String = when (tag) {
    "en" -> "English"
    "am" -> "አማርኛ"
    "ar" -> "العربية"
    "fr" -> "Français"
    "es" -> "Español"
    "pt" -> "Português"
    "zh" -> "中文"
    "hi" -> "हिन्दी"
    else -> tag
}
