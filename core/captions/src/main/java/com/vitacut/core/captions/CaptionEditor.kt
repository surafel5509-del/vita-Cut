package com.vitacut.core.captions

import com.vitacut.core.model.Caption
import com.vitacut.core.model.CaptionSet
import com.vitacut.core.model.CaptionStyle
import com.vitacut.core.model.CaptionId

/**
 * Immutable editing operations for [CaptionSet] — the engine behind the manual caption editor
 * and post-transcription cleanup. Every operation returns a new set; the caller pushes it
 * through the project's undo history.
 *
 * Pure Kotlin → fully unit-tested, no Android dependency.
 */
object CaptionEditor {

    fun add(set: CaptionSet, startUs: Long, endUs: Long, text: String): CaptionSet {
        if (endUs <= startUs) return set
        val caption = Caption(startUs = startUs, endUs = endUs, text = text)
        return set.copy(captions = (set.captions + caption).sortedBy { it.startUs })
    }

    fun updateText(set: CaptionSet, id: CaptionId, text: String): CaptionSet =
        set.mapCaption(id) { it.copy(text = text) }

    fun retime(set: CaptionSet, id: CaptionId, startUs: Long, endUs: Long): CaptionSet {
        if (endUs <= startUs) return set
        return set.mapCaption(id) { it.copy(startUs = startUs, endUs = endUs) }
            .let { it.copy(captions = it.captions.sortedBy { c -> c.startUs }) }
    }

    fun delete(set: CaptionSet, id: CaptionId): CaptionSet =
        set.copy(captions = set.captions.filterNot { it.id == id })

    /**
     * Splits a cue at [positionUs]. Without explicit texts the existing text is divided at the
     * word boundary closest to the time-proportional split point, so splits feel natural.
     */
    fun split(
        set: CaptionSet,
        id: CaptionId,
        positionUs: Long,
        textBefore: String? = null,
        textAfter: String? = null,
    ): CaptionSet {
        val caption = set.captions.firstOrNull { it.id == id } ?: return set
        if (positionUs <= caption.startUs || positionUs >= caption.endUs) return set

        val (before, after) = if (textBefore != null && textAfter != null) {
            textBefore to textAfter
        } else {
            proportionalTextSplit(caption.text, caption.startUs, caption.endUs, positionUs)
        }

        val first = Caption(
            startUs = caption.startUs,
            endUs = positionUs,
            text = before,
            wordTimingsUs = estimateWordTimings(before, caption.startUs, positionUs),
        )
        val second = Caption(
            startUs = positionUs,
            endUs = caption.endUs,
            text = after,
            wordTimingsUs = estimateWordTimings(after, positionUs, caption.endUs),
        )
        val updated = set.captions.flatMap {
            if (it.id == id) listOf(first, second) else listOf(it)
        }
        return set.copy(captions = updated.sortedBy { it.startUs })
    }

    /** Merges two cues that are adjacent in time order. */
    fun merge(set: CaptionSet, firstId: CaptionId, secondId: CaptionId): CaptionSet {
        val sorted = set.captions.sortedBy { it.startUs }
        val firstIndex = sorted.indexOfFirst { it.id == firstId }
        val secondIndex = sorted.indexOfFirst { it.id == secondId }
        if (firstIndex < 0 || secondIndex < 0) return set
        val (a, b) = if (firstIndex < secondIndex) {
            sorted[firstIndex] to sorted[secondIndex]
        } else {
            sorted[secondIndex] to sorted[firstIndex]
        }
        val merged = Caption(
            startUs = a.startUs,
            endUs = b.endUs,
            text = listOf(a.text, b.text).filter { it.isNotBlank() }.joinToString(" "),
            wordTimingsUs = estimateWordTimings(
                listOf(a.text, b.text).filter { it.isNotBlank() }.joinToString(" "),
                a.startUs,
                b.endUs,
            ),
        )
        val ids = setOf(a.id, b.id)
        return set.copy(captions = sorted.filterNot { it.id in ids } + merged)
            .let { it.copy(captions = it.captions.sortedBy { c -> c.startUs }) }
    }

    /** Fills [Caption.wordTimingsUs] with an even per-word distribution (karaoke pacing). */
    fun estimateWordTimings(text: String, startUs: Long, endUs: Long): List<Long> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || endUs <= startUs) return emptyList()
        val step = (endUs - startUs) / words.size
        return words.indices.map { it * step }
    }

    /** Ensures every cue carries karaoke timings (no-op for cues that already have them). */
    fun withEstimatedTimings(set: CaptionSet): CaptionSet = set.copy(
        captions = set.captions.map { caption ->
            if (caption.wordTimingsUs.isEmpty()) {
                caption.copy(
                    wordTimingsUs = estimateWordTimings(caption.text, caption.startUs, caption.endUs),
                )
            } else caption
        },
    )

    fun setStyle(set: CaptionSet, style: CaptionStyle): CaptionSet = set.copy(style = style)

    fun setEnabled(set: CaptionSet, enabled: Boolean): CaptionSet = set.copy(enabled = enabled)

    fun setLanguage(set: CaptionSet, languageTag: String): CaptionSet =
        set.copy(languageTag = languageTag)

    /**
     * Shifts a whole transcript by [offsetUs] — used when captions were generated from an audio
     * track that starts at a non-zero timeline position.
     */
    fun shift(set: CaptionSet, offsetUs: Long): CaptionSet = set.copy(
        captions = set.captions.map {
            it.copy(startUs = (it.startUs + offsetUs).coerceAtLeast(0L), endUs = it.endUs + offsetUs)
        },
    )

    /** Removes cues overlapping silence-removed ranges (called after auto silence removal). */
    fun removeInRange(set: CaptionSet, rangeStartUs: Long, rangeEndUs: Long): CaptionSet =
        set.copy(
            captions = set.captions.filterNot {
                it.startUs < rangeEndUs && it.endUs > rangeStartUs
            },
        )

    private fun proportionalTextSplit(
        text: String,
        startUs: Long,
        endUs: Long,
        positionUs: Long,
    ): Pair<String, String> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 2) return text to ""
        val fraction = ((positionUs - startUs).toFloat() / (endUs - startUs)).coerceIn(0f, 1f)
        val count = (words.size * fraction).toInt().coerceIn(1, words.size - 1)
        return words.take(count).joinToString(" ") to words.drop(count).joinToString(" ")
    }

    private fun CaptionSet.mapCaption(id: CaptionId, transform: (Caption) -> Caption): CaptionSet =
        copy(captions = captions.map { if (it.id == id) transform(it) else it })
}
