package com.vitacut.core.model

import kotlinx.serialization.Serializable

/** Kind of source media. Determines which tools are available for clips referencing it. */
@Serializable
enum class MediaKind {
    VIDEO,
    IMAGE,
    GIF,
    AUDIO,
}

/**
 * A media file referenced by a project.
 *
 * Projects store **references** (URIs), never file contents. On reopen, the repository re-validates
 * each URI and marks assets [available] = false when the user removed the file, so the UI can show
 * an honest "media missing" state instead of crashing or rendering black frames silently.
 */
@Serializable
data class MediaAsset(
    val id: AssetId = AssetId(),
    /** content:// (Photo Picker / SAF / MediaStore) or file:// URI string. */
    val uri: String,
    val kind: MediaKind,
    val mimeType: String = "",
    val displayName: String = "",
    /** For video/audio: total source duration; for images/GIF: 0. */
    val durationUs: Long = 0L,
    /** Natural (pre-rotation) pixel size. */
    val width: Int = 0,
    val height: Int = 0,
    /** Display rotation stored in the container (0/90/180/270). */
    val rotationDegrees: Int = 0,
    val frameRate: Float = 0f,
    val sizeBytes: Long = 0L,
    val dateAddedMs: Long = 0L,
    /** False when the container/codec is known to be undecodable on this device. */
    val codecSupported: Boolean = true,
    /**
     * Optional URI of a lower-resolution proxy generated for PERFORMANCE mode. When present, the
     * preview and export may use it (export only when the user enabled proxy export).
     */
    val proxyUri: String? = null,
    /** Set at load time (not persisted meaningfully): whether the URI still resolves. */
    val available: Boolean = true,
) {
    val isVisual: Boolean get() = kind != MediaKind.AUDIO

    val hasAudioTrack: Boolean get() = kind == MediaKind.VIDEO

    /** Effective display size after container rotation. */
    val displayWidth: Int
        get() = if (rotationDegrees == 90 || rotationDegrees == 270) height else width
    val displayHeight: Int
        get() = if (rotationDegrees == 90 || rotationDegrees == 270) width else height

    /** URI actually used for playback/decoding (proxy when available and requested). */
    fun playbackUri(useProxy: Boolean): String =
        if (useProxy && proxyUri != null) proxyUri else uri
}
