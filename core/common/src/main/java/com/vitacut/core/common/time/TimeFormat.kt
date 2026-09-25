package com.vitacut.core.common.time

import java.util.Locale
import kotlin.math.abs

/** Microseconds are the canonical time unit in Vita Cut. */
const val MICROS_PER_SECOND = 1_000_000L
const val MICROS_PER_MILLI = 1_000L
const val NANOS_PER_MICRO = 1_000L

/**
 * Formats [timeUs] as `H:MM:SS.mmm`, `M:SS.mmm`, or `SS.mmm` depending on magnitude.
 * Used for the transport clock and timeline ruler labels.
 */
fun formatTimecode(timeUs: Long, showMillis: Boolean = true): String {
    val sign = if (timeUs < 0) "-" else ""
    val abs = abs(timeUs)
    val totalSeconds = abs / MICROS_PER_SECOND
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val millis = (abs % MICROS_PER_SECOND) / MICROS_PER_MILLI
    return buildString {
        append(sign)
        if (hours > 0) append("$hours:")
        append(String.format(Locale.US, "%02d:%02d", minutes, seconds))
        if (showMillis) append(String.format(Locale.US, ".%03d", millis))
    }
}

/** Formats a duration compactly, e.g. `1:23:45` or `0:45`. */
fun formatDuration(timeUs: Long): String = formatTimecode(timeUs, showMillis = false)

/** Duration of one frame at [frameRate], in microseconds. */
fun frameDurationUs(frameRate: Float): Long =
    if (frameRate <= 0f) 33_333L else (MICROS_PER_SECOND / frameRate).toLong()

/** Snaps [timeUs] to the nearest frame boundary for [frameRate]. */
fun snapToFrame(timeUs: Long, frameRate: Float): Long {
    val frame = frameDurationUs(frameRate)
    if (frame <= 0) return timeUs
    return ((timeUs + frame / 2) / frame) * frame
}
