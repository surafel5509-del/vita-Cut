package com.vitacut.core.model

import kotlinx.serialization.Serializable

/** A single subtitle cue. Times are absolute timeline positions. */
@Serializable
data class Caption(
    val id: CaptionId = CaptionId(),
    val startUs: Long,
    val endUs: Long,
    val text: String,
    /** Word-level timing (start offsets in µs relative to cue start, one per whitespace token). */
    val wordTimingsUs: List<Long> = emptyList(),
) {
    val durationUs: Long get() = (endUs - startUs).coerceAtLeast(0L)
}

/** Vertical anchor of burned-in captions. */
@Serializable
enum class CaptionPosition { TOP, CENTER, BOTTOM }

/** Caption entrance animation. */
@Serializable
enum class CaptionAnimation { NONE, FADE, POP, KARAOKE }

@Serializable
data class CaptionStyle(
    val fontFamilyKey: String = "default",
    /** Fraction of canvas height. */
    val sizeFraction: Float = 0.045f,
    val bold: Boolean = true,
    val colorArgb: Int = 0xFFFFFFFF.toInt(),
    val strokeColorArgb: Int = 0xFF000000.toInt(),
    val strokeWidthFraction: Float = 0.06f,
    val backgroundColorArgb: Int = 0x00000000,
    val backgroundCornerRadiusFraction: Float = 0.01f,
    val position: CaptionPosition = CaptionPosition.BOTTOM,
    /** Vertical offset from the anchor, in canvas-height fractions. */
    val offsetFraction: Float = 0.08f,
    val animation: CaptionAnimation = CaptionAnimation.NONE,
    /** Karaoke-style highlight color for the active word. */
    val highlightColorArgb: Int = 0xFFFFD54F.toInt(),
)

/** The full caption set of a project (auto-generated and/or manually edited). */
@Serializable
data class CaptionSet(
    val captions: List<Caption> = emptyList(),
    val style: CaptionStyle = CaptionStyle(),
    val enabled: Boolean = true,
    /** BCP-47 tag of the detected/selected source language. */
    val languageTag: String = "en",
) {
    val isEmpty: Boolean get() = captions.isEmpty()

    companion object {
        val EMPTY = CaptionSet()
    }
}

/**
 * SRT / WebVTT writers. Kept in the model module (pure Kotlin) so they are unit-testable and
 * usable from both the editor and the export flow.
 */
object CaptionFormats {

    fun toSrt(captions: List<Caption>): String = buildString {
        captions.sortedBy { it.startUs }.forEachIndexed { index, caption ->
            appendLine(index + 1)
            appendLine("${srtTime(caption.startUs)} --> ${srtTime(caption.endUs)}")
            appendLine(caption.text)
            appendLine()
        }
    }

    fun toVtt(captions: List<Caption>): String = buildString {
        appendLine("WEBVTT")
        appendLine()
        captions.sortedBy { it.startUs }.forEach { caption ->
            appendLine("${srtTime(caption.startUs)} --> ${srtTime(caption.endUs)}")
            appendLine(caption.text)
            appendLine()
        }
    }

    /** `HH:MM:SS,mmm` (SRT) — VTT accepts the same layout with '.' but ',' is tolerated widely; we emit SRT style for both minus the header difference. */
    private fun srtTime(timeUs: Long): String {
        val clamped = timeUs.coerceAtLeast(0L)
        val hours = clamped / 3_600_000_000L
        val minutes = (clamped % 3_600_000_000L) / 60_000_000L
        val seconds = (clamped % 60_000_000L) / 1_000_000L
        val millis = (clamped % 1_000_000L) / 1_000L
        return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, millis)
    }

    /** Minimal SRT parser so users can import existing subtitle files. */
    fun parseSrt(content: String): List<Caption> {
        val captions = mutableListOf<Caption>()
        val blocks = content.replace("\r\n", "\n").split("\n\n")
        for (block in blocks) {
            val lines = block.split("\n").filter { it.isNotBlank() }
            if (lines.size < 2) continue
            val timingLine = lines.firstOrNull { it.contains("-->") } ?: continue
            val parts = timingLine.split("-->").map { it.trim() }
            if (parts.size != 2) continue
            val start = parseSrtTime(parts[0]) ?: continue
            val end = parseSrtTime(parts[1]) ?: continue
            val textIndex = lines.indexOfFirst { it.contains("-->") } + 1
            val text = lines.subList(textIndex, lines.size).joinToString("\n")
            if (text.isNotBlank() && end > start) {
                captions += Caption(startUs = start, endUs = end, text = text)
            }
        }
        return captions
    }

    private fun parseSrtTime(value: String): Long? {
        val normalized = value.replace('.', ':').replace(',', ':')
        val parts = normalized.split(":")
        if (parts.size != 4) return null
        val hours = parts[0].toLongOrNull() ?: return null
        val minutes = parts[1].toLongOrNull() ?: return null
        val seconds = parts[2].toLongOrNull() ?: return null
        val millis = parts[3].toLongOrNull() ?: return null
        return ((hours * 3600 + minutes * 60 + seconds) * 1000 + millis) * 1000L
    }
}
