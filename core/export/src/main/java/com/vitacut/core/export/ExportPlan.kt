package com.vitacut.core.export

import com.vitacut.core.common.device.DeviceCapabilities
import com.vitacut.core.model.ExportBitrateMode
import com.vitacut.core.model.ExportFrameRate
import com.vitacut.core.model.ExportResolution
import com.vitacut.core.model.ExportSettings
import com.vitacut.core.model.ExportVideoCodec
import com.vitacut.core.model.Project

/** A single graceful degradation applied to the user's export request. */
data class ExportFallback(
    /** String resource key explaining the fallback, e.g. "export_fallback_hevc". */
    val messageKey: String,
)

/**
 * The validated, device-adjusted export plan.
 *
 * Validation never fails an export outright for capability reasons — it degrades (HEVC→H.264,
 * 4K→2K→1080p, 60→30) and reports each adjustment so the export screen can be honest with the
 * user. Hard failures remain: empty timeline, no writable storage.
 */
data class ExportPlan(
    val settings: ExportSettings,
    val outputWidth: Int,
    val outputHeight: Int,
    val videoBitrateBps: Int,
    val audioBitrateBps: Int,
    val fallbacks: List<ExportFallback>,
)

/** Hard preconditions that must hold before any export starts. */
sealed interface ExportValidation {
    data class Ready(val plan: ExportPlan) : ExportValidation
    data class Invalid(val messageKey: String) : ExportValidation
}

/**
 * Pure-data snapshot of the encoder capabilities the planner needs. Keeping [ExportPlanner] free
 * of Android types makes the whole degradation ladder unit-testable on the JVM.
 */
data class CapabilitySnapshot(
    val supportsHevcEncode: Boolean = true,
    val supports4kExport: Boolean = true,
    val supports2kExport: Boolean = true,
    val supports60Fps: Boolean = true,
    val maxEncodeWidth: Int = 4096,
    val maxEncodeHeight: Int = 4096,
) {
    companion object {
        /** A permissive snapshot for tests and emulators without codec probing. */
        val UNRESTRICTED = CapabilitySnapshot()

        /** A conservative low-end device: 1080p H.264, no HEVC, no 60fps. */
        val LOW_END = CapabilitySnapshot(
            supportsHevcEncode = false,
            supports4kExport = false,
            supports2kExport = false,
            supports60Fps = false,
            maxEncodeWidth = 1920,
            maxEncodeHeight = 1920,
        )
    }
}

/** Extracts the planner-relevant snapshot from the live [DeviceCapabilities] probe. */
fun DeviceCapabilities.snapshot(): CapabilitySnapshot = CapabilitySnapshot(
    supportsHevcEncode = supportsHevcEncode,
    supports4kExport = supports4kExport,
    supports2kExport = supports2kExport,
    supports60Fps = supports60Fps,
    maxEncodeWidth = maxH264EncodeSize.width,
    maxEncodeHeight = maxH264EncodeSize.height,
)

object ExportPlanner {

    /**
     * Turns user [requested] settings + the [project] canvas into a concrete [ExportPlan],
     * adapting to [capabilities].
     */
    fun plan(
        requested: ExportSettings,
        project: Project,
        capabilities: CapabilitySnapshot,
    ): ExportValidation {
        if (project.durationUs <= 0L) {
            return ExportValidation.Invalid("export_error_empty_timeline")
        }

        val fallbacks = mutableListOf<ExportFallback>()
        var settings = requested

        // Codec fallback.
        if (settings.videoCodec == ExportVideoCodec.HEVC && !capabilities.supportsHevcEncode) {
            settings = settings.copy(videoCodec = ExportVideoCodec.H264)
            fallbacks += ExportFallback("export_fallback_hevc")
        }

        // Resolution fallback ladder.
        var resolution = settings.resolution
        val maxPixels = capabilities.maxEncodeWidth.toLong() * capabilities.maxEncodeHeight
        fun fits(r: ExportResolution): Boolean {
            val (w, h) = outputSizeFor(r, project)
            return w.toLong() * h <= maxPixels * 1.05 &&
                (r != ExportResolution.P4K || capabilities.supports4kExport) &&
                (r != ExportResolution.P2K || capabilities.supports2kExport)
        }
        if (!fits(resolution)) {
            val ladder = listOf(
                ExportResolution.P4K, ExportResolution.P2K,
                ExportResolution.P1080, ExportResolution.P720, ExportResolution.P480,
            )
            val degraded = ladder.firstOrNull { fits(it) && it.height < resolution.height }
                ?: ExportResolution.P480
            if (degraded != resolution) {
                fallbacks += ExportFallback("export_fallback_resolution")
                resolution = degraded
                settings = settings.copy(resolution = degraded)
            }
        }

        // Frame-rate fallback.
        if (settings.frameRate == ExportFrameRate.FPS_60 && !capabilities.supports60Fps) {
            settings = settings.copy(frameRate = ExportFrameRate.FPS_30)
            fallbacks += ExportFallback("export_fallback_fps")
        }

        val (width, height) = outputSizeFor(resolution, project)
        // Encoders demand even dimensions.
        val evenWidth = (width / 2) * 2
        val evenHeight = (height / 2) * 2

        // Scale the bitrate heuristic for the actual canvas aspect (models assume 16:9).
        val aspect = evenWidth.toFloat() / evenHeight.coerceAtLeast(1)
        val baseBitrateKbps = settings.bitrateKbps()
        val adjustedKbps = (baseBitrateKbps * (aspect / (16f / 9f)).coerceIn(0.5f, 1.2f)).toInt()

        return ExportValidation.Ready(
            ExportPlan(
                settings = settings,
                outputWidth = evenWidth.coerceAtLeast(64),
                outputHeight = evenHeight.coerceAtLeast(64),
                videoBitrateBps = adjustedKbps.coerceIn(500, 120_000) * 1000,
                audioBitrateBps = settings.audioBitrate.kbps * 1000,
                fallbacks = fallbacks,
            ),
        )
    }

    /**
     * Output pixel size for a resolution choice, honoring the project's canvas aspect:
     * "1080p" means 1080 on the *long* edge for portrait canvases and on height for landscape.
     */
    fun outputSizeFor(resolution: ExportResolution, project: Project): Pair<Int, Int> {
        val target = resolution.height
        val canvasAspect = project.canvas.aspect
        return if (canvasAspect >= 1f) {
            // Landscape: target is the height.
            val width = (target * canvasAspect).toInt()
            width to target
        } else {
            // Portrait: target is the width (a 1080x1920 "1080p vertical" video).
            val height = (target / canvasAspect).toInt()
            target to height
        }
    }

    /** Rough output size estimate for the storage guard, in bytes. */
    fun estimatedOutputBytes(plan: ExportPlan, durationUs: Long): Long {
        val seconds = durationUs / 1_000_000.0
        return ((plan.videoBitrateBps + plan.audioBitrateBps) / 8.0 * seconds).toLong()
    }
}
