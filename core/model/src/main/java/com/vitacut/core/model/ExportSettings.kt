package com.vitacut.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ExportResolution(val height: Int, val label: String) {
    P480(480, "480p"),
    P720(720, "720p"),
    P1080(1080, "1080p"),
    P2K(1440, "2K"),
    P4K(2160, "4K"),
}

@Serializable
enum class ExportFrameRate(val fps: Float, val label: String) {
    FPS_24(24f, "24"),
    FPS_25(25f, "25"),
    FPS_30(30f, "30"),
    FPS_50(50f, "50"),
    FPS_60(60f, "60"),
}

@Serializable
enum class ExportBitrateMode { LOW, MEDIUM, HIGH, CUSTOM }

@Serializable
enum class ExportVideoCodec(val mimeSuffix: String) {
    H264("avc"),
    HEVC("hevc"),
}

@Serializable
enum class ExportAudioBitrate(val kbps: Int, val label: String) {
    KBPS_128(128, "128 kbps"),
    KBPS_192(192, "192 kbps"),
    KBPS_256(256, "256 kbps"),
    KBPS_320(320, "320 kbps"),
}

/**
 * Export configuration chosen on the export screen.
 *
 * [ExportEngine] validates this against device capabilities and degrades gracefully (e.g. HEVC →
 * H.264, 4K → 2K) reporting the applied fallbacks back to the UI instead of failing.
 */
@Serializable
data class ExportSettings(
    val resolution: ExportResolution = ExportResolution.P1080,
    val frameRate: ExportFrameRate = ExportFrameRate.FPS_30,
    val bitrateMode: ExportBitrateMode = ExportBitrateMode.MEDIUM,
    /** Only for [ExportBitrateMode.CUSTOM]. */
    val customBitrateKbps: Int = 12_000,
    val videoCodec: ExportVideoCodec = ExportVideoCodec.H264,
    val audioBitrate: ExportAudioBitrate = ExportAudioBitrate.KBPS_256,
    val includeCaptions: Boolean = true,
    /** Use generated proxy media for preview-heavy sources (faster export, lower quality). */
    val useProxies: Boolean = false,
) {
    fun bitrateKbps(): Int = when (bitrateMode) {
        ExportBitrateMode.LOW -> lowBitrate()
        ExportBitrateMode.MEDIUM -> mediumBitrate()
        ExportBitrateMode.HIGH -> highBitrate()
        ExportBitrateMode.CUSTOM -> customBitrateKbps.coerceIn(500, 120_000)
    }

    private fun lowBitrate() = kbpsFor(0.04)
    private fun mediumBitrate() = kbpsFor(0.07)
    private fun highBitrate() = kbpsFor(0.11)

    /** Bits-per-pixel heuristic (bpp × pixels/sec), a standard H.264/HEVC sizing rule. */
    private fun kbpsFor(bpp: Double): Int =
        (bpp * resolution.height * resolution.height * aspectDefault * frameRate.fps / 1000.0)
            .toInt()
            .coerceIn(800, 120_000)

    companion object {
        /** Width/height factor for 16:9-class content; canvas aspect overrides at engine level. */
        const val aspectDefault: Double = 16.0 / 9.0

        val DEFAULT = ExportSettings()
    }
}
