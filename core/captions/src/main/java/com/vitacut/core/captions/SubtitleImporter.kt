package com.vitacut.core.captions

import com.vitacut.core.model.Caption
import com.vitacut.core.model.CaptionFormats

/** Recognized subtitle container formats. */
enum class SubtitleFormat { SRT, VTT, UNKNOWN }

/**
 * Import of user subtitle files (.srt / .vtt).
 *
 * VTT is normalized into the SRT dialect the lenient [CaptionFormats.parseSrt] understands:
 * - the `WEBVTT` header block is dropped,
 * - short `MM:SS.mmm` / `SS.mmm` timestamps are padded to `HH:MM:SS,mmm`,
 * - inline cue tags (`<c>`, `<b>`, …) and cue settings after the arrow are stripped.
 *
 * Import never throws: unreadable files yield an empty list, which the UI reports as
 * "could not read subtitles".
 */
object SubtitleImporter {

    fun detectFormat(content: String): SubtitleFormat {
        val head = content.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return when {
            head.startsWith("WEBVTT") -> SubtitleFormat.VTT
            content.contains("-->") -> SubtitleFormat.SRT
            else -> SubtitleFormat.UNKNOWN
        }
    }

    fun import(content: String): List<Caption> = when (detectFormat(content)) {
        SubtitleFormat.UNKNOWN -> emptyList()
        SubtitleFormat.SRT -> runCatching { CaptionFormats.parseSrt(content) }.getOrDefault(emptyList())
        SubtitleFormat.VTT -> runCatching { CaptionFormats.parseSrt(normalizeVtt(content)) }
            .getOrDefault(emptyList())
    }

    /** Exports are delegated to the model so editor & export flows share one writer. */
    fun exportSrt(captions: List<Caption>): String = CaptionFormats.toSrt(captions)

    fun exportVtt(captions: List<Caption>): String = CaptionFormats.toVtt(captions)

    /**
     * Splits the VTT into blank-line-delimited blocks, drops WEBVTT/NOTE/STYLE/REGION blocks,
     * and rewrites each cue block in SRT layout (index, normalized timing, tag-free text).
     */
    internal fun normalizeVtt(content: String): String {
        val blocks = content.replace("\r\n", "\n").split(Regex("\n[ \t]*\n"))
        val out = StringBuilder()
        var index = 1
        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            val first = lines.first()
            if (first.startsWith("WEBVTT") || first.startsWith("NOTE") ||
                first.startsWith("STYLE") || first.startsWith("REGION")
            ) {
                continue
            }
            val timingLine = lines.firstOrNull { it.contains("-->") } ?: continue
            val timingIndex = lines.indexOf(timingLine)
            val text = lines.subList(timingIndex + 1, lines.size)
                .joinToString("\n") { it.replace(Regex("<[^>]*>"), "") }
            if (text.isBlank()) continue
            out.appendLine(index++)
            out.appendLine(normalizeTiming(timingLine))
            out.appendLine(text)
            out.appendLine()
        }
        return out.toString()
    }

    private fun normalizeTiming(line: String): String {
        val parts = line.split("-->")
        val start = padTimestamp(parts[0].trim())
        // VTT allows cue settings after the end time; keep only the timestamp.
        val end = padTimestamp(parts.getOrNull(1)?.trim()?.split(Regex("\\s+"))?.first().orEmpty())
        return "$start --> $end"
    }

    private fun padTimestamp(value: String): String {
        if (value.isBlank()) return "00:00:00,000"
        val parts = value.split(":", ".")
        return when (parts.size) {
            // SS.mmm
            2 -> "00:00:%02d,%s".format(parts[0].toIntOrNull() ?: 0, parts[1].padEnd(3, '0').take(3))
            // MM:SS.mmm or MM:SS,mmm
            3 -> "00:%s".format(value.replace('.', ','))
            // HH:MM:SS.mmm
            4 -> "%s:%s:%s,%s".format(parts[0], parts[1], parts[2], parts[3].padEnd(3, '0').take(3))
            else -> value.replace('.', ',')
        }
    }
}
