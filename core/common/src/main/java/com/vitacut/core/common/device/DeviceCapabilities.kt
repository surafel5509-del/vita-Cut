package com.vitacut.core.common.device

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.opengl.EGL14
import android.os.Build
import android.util.Size
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Preview / processing quality tiers. See the "Low-end device mode" product requirement. */
enum class PerformanceMode {
    /** Maximum preview resolution, full effects, no proxies unless the source is huge. */
    HIGH_QUALITY,

    /** Balanced defaults — used when the device comfortably clears the requirements. */
    BALANCED,

    /** Halved preview resolution, proxy media, reduced effect complexity. */
    PERFORMANCE,
}

/**
 * Immutable snapshot of what this device can do, computed once at startup.
 *
 * The editor, preview and export layers all consult this object instead of re-querying the
 * platform, which keeps decisions consistent and avoids expensive [MediaCodecList] scans at
 * inconvenient times.
 */
@Singleton
class DeviceCapabilities @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    val totalMemoryMb: Long by lazy {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        info.totalMem / (1024 * 1024)
    }

    val cpuCores: Int get() = Runtime.getRuntime().availableProcessors()

    val supportsHevcEncode: Boolean by lazy { supportsEncoder("video/hevc") }

    val supportsHevcDecode: Boolean by lazy { supportsDecoder("video/hevc") }

    /** Largest H.264 encode size the platform advertises (0 = unknown). */
    val maxH264EncodeSize: Size by lazy { maxEncoderSize("video/avc") }

    val supports4kExport: Boolean by lazy {
        val size = maxH264EncodeSize
        size.width >= 3840 && size.height >= 2160 || supportsHevcEncode && maxEncoderSize("video/hevc").width >= 3840
    }

    val supports2kExport: Boolean by lazy {
        maxH264EncodeSize.width >= 2560 || supportsHevcEncode
    }

    val supports60Fps: Boolean by lazy { Build.VERSION.SDK_INT >= 26 && cpuCores >= 4 }

    val supportsHighFrameRatePlayback: Boolean by lazy { supports60Fps && totalMemoryMb >= 3072 }

    /**
     * Recommends a [PerformanceMode] based on RAM, cores and Android version. Users can always
     * override this in Settings.
     */
    fun recommendedPerformanceMode(): PerformanceMode = when {
        totalMemoryMb < 3072 || cpuCores < 4 -> PerformanceMode.PERFORMANCE
        totalMemoryMb < 6144 -> PerformanceMode.BALANCED
        else -> PerformanceMode.HIGH_QUALITY
    }

    private val encoderMimeTypes: Set<String> by lazy { codecMimeTypes(encoder = true) }
    private val decoderMimeTypes: Set<String> by lazy { codecMimeTypes(encoder = false) }

    private fun codecMimeTypes(encoder: Boolean): Set<String> = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder == encoder }
            .flatMap { it.supportedTypes.asIterable() }
            .map { it.lowercase() }
            .toSet()
    } catch (t: Throwable) {
        emptySet()
    }

    private fun supportsEncoder(mime: String): Boolean = mime in encoderMimeTypes
    private fun supportsDecoder(mime: String): Boolean = mime in decoderMimeTypes

    private fun maxEncoderSize(mime: String): Size = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .asSequence()
            .filter { it.isEncoder }
            .mapNotNull { info ->
                runCatching {
                    val caps = info.getCapabilitiesForType(mime).videoCapabilities ?: return@mapNotNull null
                    Size(caps.supportedWidths.upper, caps.supportedHeights.upper)
                }.getOrNull()
            }
            .maxByOrNull { it.width * it.height } ?: Size(1920, 1080)
    } catch (t: Throwable) {
        Size(1920, 1080)
    }

    /** GLES 3.0 is required by the Media3 effect pipeline; report availability for fallbacks. */
    val supportsGles3: Boolean by lazy {
        runCatching {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            EGL14.eglInitialize(display, version, 0, version, 1) && version[0] >= 3
        }.getOrDefault(false)
    }

    /** Whether the on-device SpeechRecognizer service is present. */
    val isLowRamDevice: Boolean by lazy {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.isLowRamDevice
    }

    /** Suggested maximum preview texture height for [PerformanceMode]. */
    fun previewHeightFor(mode: PerformanceMode): Int = when (mode) {
        PerformanceMode.HIGH_QUALITY -> 1080
        PerformanceMode.BALANCED -> 720
        PerformanceMode.PERFORMANCE -> 540
    }

    /** Suggested maximum proxy generation source size; larger sources get proxies in PERFORMANCE mode. */
    fun proxyThresholdBytesFor(mode: PerformanceMode): Long = when (mode) {
        PerformanceMode.HIGH_QUALITY -> Long.MAX_VALUE
        PerformanceMode.BALANCED -> 400L * 1024 * 1024
        PerformanceMode.PERFORMANCE -> 100L * 1024 * 1024
    }

    fun describeEncoderSupport(codecInfo: MediaCodecInfo): String =
        codecInfo.supportedTypes.joinToString()
}
