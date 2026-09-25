package com.vitacut.feature.export

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vitacut.core.designsystem.R
import com.vitacut.core.designsystem.components.VitaButton
import com.vitacut.core.designsystem.components.VitaChipItem
import com.vitacut.core.designsystem.components.VitaChipRow
import com.vitacut.core.designsystem.components.VitaLabeledSlider
import com.vitacut.core.designsystem.components.VitaOutlinedButton
import com.vitacut.core.designsystem.components.VitaTopBar
import com.vitacut.core.designsystem.strings.VitaStrings
import com.vitacut.core.model.ExportAudioBitrate
import com.vitacut.core.model.ExportBitrateMode
import com.vitacut.core.model.ExportFrameRate
import com.vitacut.core.model.ExportResolution
import com.vitacut.core.model.ExportVideoCodec

/**
 * Export sheet-as-screen: quality options with a live device-adjusted plan preview (fallbacks +
 * estimated size), then progress/ETA/cancel while the background worker runs, then open/share.
 */
@Composable
fun ExportScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ExportViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        modifier = modifier,
        topBar = {
            VitaTopBar(
                title = stringResource(R.string.export_title),
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
            if (state.isExporting || state.result != null) {
                ExportProgressPane(
                    state = state,
                    etaMs = viewModel.etaMs(state),
                    onCancel = viewModel::cancelExport,
                    onOpen = { uri ->
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(Uri.parse(uri), "video/mp4")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            )
                        }
                    },
                    onShare = { uri ->
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_SEND)
                                    .setType("video/mp4")
                                    .putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            )
                        }
                    },
                    onDone = {
                        viewModel.consumeResult()
                        onBack()
                    },
                )
            } else {
                ExportOptionsPane(state = state, viewModel = viewModel)
            }
        }
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
private fun ExportOptionsPane(state: ExportUiState, viewModel: ExportViewModel) {
    val context = LocalContext.current
    val settings = state.settings

    SectionLabel(stringResource(R.string.export_resolution))
    VitaChipRow(
        chips = ExportResolution.entries.map {
            VitaChipItem(it.name, it.label, settings.resolution == it)
        },
        onChipClick = { id ->
            viewModel.updateSettings { it.copy(resolution = ExportResolution.valueOf(id)) }
        },
    )

    SectionLabel(stringResource(R.string.export_frame_rate))
    VitaChipRow(
        chips = ExportFrameRate.entries.map {
            VitaChipItem(it.name, it.label, settings.frameRate == it)
        },
        onChipClick = { id ->
            viewModel.updateSettings { it.copy(frameRate = ExportFrameRate.valueOf(id)) }
        },
    )

    SectionLabel(stringResource(R.string.export_codec))
    VitaChipRow(
        chips = ExportVideoCodec.entries.map {
            VitaChipItem(
                it.name,
                if (it == ExportVideoCodec.HEVC) "H.265" else "H.264",
                settings.videoCodec == it,
            )
        },
        onChipClick = { id ->
            viewModel.updateSettings { it.copy(videoCodec = ExportVideoCodec.valueOf(id)) }
        },
    )

    SectionLabel(stringResource(R.string.export_bitrate))
    VitaChipRow(
        chips = listOf(
            VitaChipItem(ExportBitrateMode.LOW.name, stringResource(R.string.export_bitrate_low), settings.bitrateMode == ExportBitrateMode.LOW),
            VitaChipItem(ExportBitrateMode.MEDIUM.name, stringResource(R.string.export_bitrate_medium), settings.bitrateMode == ExportBitrateMode.MEDIUM),
            VitaChipItem(ExportBitrateMode.HIGH.name, stringResource(R.string.export_bitrate_high), settings.bitrateMode == ExportBitrateMode.HIGH),
            VitaChipItem(ExportBitrateMode.CUSTOM.name, stringResource(R.string.export_bitrate_custom), settings.bitrateMode == ExportBitrateMode.CUSTOM),
        ),
        onChipClick = { id ->
            viewModel.updateSettings { it.copy(bitrateMode = ExportBitrateMode.valueOf(id)) }
        },
    )
    if (settings.bitrateMode == ExportBitrateMode.CUSTOM) {
        VitaLabeledSlider(
            label = "${settings.customBitrateKbps / 1000} Mbps",
            value = settings.customBitrateKbps.toFloat(),
            onValueChange = { kbps ->
                viewModel.updateSettings { it.copy(customBitrateKbps = kbps.toInt()) }
            },
            modifier = Modifier.padding(horizontal = 16.dp),
            valueRange = 500f..60_000f,
            valueText = "${settings.customBitrateKbps} kbps",
        )
    }

    SectionLabel(stringResource(R.string.export_audio_bitrate))
    VitaChipRow(
        chips = ExportAudioBitrate.entries.map {
            VitaChipItem(it.name, it.label, settings.audioBitrate == it)
        },
        onChipClick = { id ->
            viewModel.updateSettings { it.copy(audioBitrate = ExportAudioBitrate.valueOf(id)) }
        },
    )

    // Plan summary: output size + estimate.
    Column(modifier = Modifier.padding(16.dp)) {
        if (state.outputWidth > 0) {
            Text(
                "${state.outputWidth}×${state.outputHeight} · " +
                    stringResource(
                        R.string.export_estimate,
                        android.text.format.Formatter.formatShortFileSize(context, state.estimatedBytes),
                    ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        state.fallbackKeys.forEach { key ->
            Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    VitaStrings.localized(context, key),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.invalidKey?.let { key ->
            Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    VitaStrings.localized(context, key),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        VitaButton(
            text = stringResource(R.string.export_start),
            onClick = viewModel::startExport,
            enabled = state.invalidKey == null && state.durationUs > 0L,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        )
    }
}

@Composable
private fun ExportProgressPane(
    state: ExportUiState,
    etaMs: Long?,
    onCancel: () -> Unit,
    onOpen: (String) -> Unit,
    onShare: (String) -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val record = state.result
    Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
        when {
            record?.status == com.vitacut.domain.repository.ExportJobStatus.SUCCEEDED -> {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(R.string.export_completed),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Row(
                    modifier = Modifier.padding(top = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    record.publishedUri?.let { uri ->
                        VitaOutlinedButton(
                            text = stringResource(R.string.export_open_file),
                            onClick = { onOpen(uri) },
                        )
                        VitaButton(
                            text = stringResource(R.string.export_share),
                            onClick = { onShare(uri) },
                        )
                    }
                }
                VitaOutlinedButton(
                    text = stringResource(R.string.action_done),
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }

            record != null -> {
                // Failed or cancelled — the error toast surfaced the key; offer a way back.
                Text(
                    record.errorMessage?.let { VitaStrings.localized(context, it.substringBefore(':')) }
                        ?: stringResource(R.string.error_unknown),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                VitaOutlinedButton(
                    text = stringResource(R.string.action_close),
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                )
            }

            else -> {
                Text(
                    stringResource(R.string.export_running, state.progressPercent),
                    style = MaterialTheme.typography.titleMedium,
                )
                LinearProgressIndicator(
                    progress = { state.progressPercent / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                etaMs?.let { eta ->
                    Text(
                        stringResource(
                            R.string.export_eta,
                            formatEta(eta),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                VitaOutlinedButton(
                    text = stringResource(R.string.export_cancel),
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                )
                Text(
                    stringResource(R.string.export_background_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp),
        style = MaterialTheme.typography.labelMedium,
    )
}

private fun formatEta(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(1)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}
