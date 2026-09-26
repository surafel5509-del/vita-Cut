package com.vitacut.core.captions

import com.vitacut.core.model.Caption
import com.vitacut.core.model.CaptionId
import com.vitacut.core.model.CaptionSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionEditorTest {

    private val idA = CaptionId("a")
    private val idB = CaptionId("b")

    private fun sampleSet() = CaptionSet(
        captions = listOf(
            Caption(id = idA, startUs = 0L, endUs = 2_000_000L, text = "Hello world again"),
            Caption(id = idB, startUs = 3_000_000L, endUs = 5_000_000L, text = "Second cue"),
        ),
    )

    @Test
    fun `add inserts sorted and rejects inverted ranges`() {
        val set = CaptionEditor.add(sampleSet(), 2_200_000L, 2_800_000L, "Middle")
        assertEquals(3, set.captions.size)
        assertEquals(listOf(0L, 2_200_000L, 3_000_000L), set.captions.map { it.startUs })

        val unchanged = CaptionEditor.add(sampleSet(), 5_000_000L, 4_000_000L, "Bad")
        assertEquals(2, unchanged.captions.size)
    }

    @Test
    fun `updateText and delete target the right cue`() {
        val edited = CaptionEditor.updateText(sampleSet(), idA, "Changed")
        assertEquals("Changed", edited.captions.first { it.id == idA }.text)
        assertEquals("Second cue", edited.captions.first { it.id == idB }.text)

        val deleted = CaptionEditor.delete(edited, idA)
        assertEquals(1, deleted.captions.size)
        assertEquals(idB, deleted.captions.single().id)
    }

    @Test
    fun `retime keeps order`() {
        val moved = CaptionEditor.retime(sampleSet(), idB, 100_000L, 900_000L)
        assertEquals(listOf(idA, idB), moved.captions.map { it.id })
        assertEquals(100_000L, moved.captions[1].startUs)
    }

    @Test
    fun `split divides text proportionally at word boundary`() {
        val split = CaptionEditor.split(sampleSet(), idA, 1_000_000L)
        assertEquals(3, split.captions.size)
        val first = split.captions.first()
        val second = split.captions[1]
        assertEquals(0L, first.startUs)
        assertEquals(1_000_000L, first.endUs)
        assertEquals(1_000_000L, second.startUs)
        assertEquals(2_000_000L, second.endUs)
        // "Hello world again" split at 50% → 1 word before ("Hello"), 2 after.
        assertEquals("Hello", first.text)
        assertEquals("world again", second.text)
        assertTrue(second.wordTimingsUs.isNotEmpty())
    }

    @Test
    fun `split with explicit texts honors them`() {
        val split = CaptionEditor.split(sampleSet(), idA, 800_000L, "Hi", "There")
        assertEquals("Hi", split.captions.first().text)
        assertEquals("There", split.captions[1].text)
    }

    @Test
    fun `split outside the cue is a no-op`() {
        val unchanged = CaptionEditor.split(sampleSet(), idA, 2_500_000L)
        assertEquals(2, unchanged.captions.size)
    }

    @Test
    fun `merge joins adjacent cues in time order`() {
        val merged = CaptionEditor.merge(sampleSet(), idB, idA) // order-insensitive
        assertEquals(1, merged.captions.size)
        val cue = merged.captions.single()
        assertEquals(0L, cue.startUs)
        assertEquals(5_000_000L, cue.endUs)
        assertEquals("Hello world again Second cue", cue.text)
    }

    @Test
    fun `estimateWordTimings distributes evenly`() {
        val timings = CaptionEditor.estimateWordTimings("one two three four", 1_000_000L, 3_000_000L)
        assertEquals(listOf(0L, 500_000L, 1_000_000L, 1_500_000L), timings)
        assertTrue(CaptionEditor.estimateWordTimings("", 0L, 1L).isEmpty())
    }

    @Test
    fun `withEstimatedTimings only fills empty timings`() {
        val set = CaptionSet(
            captions = listOf(
                Caption(startUs = 0L, endUs = 1_000_000L, text = "a b"),
                Caption(startUs = 1L, endUs = 2L, text = "c", wordTimingsUs = listOf(42L)),
            ),
        )
        val filled = CaptionEditor.withEstimatedTimings(set)
        assertEquals(2, filled.captions[0].wordTimingsUs.size)
        assertEquals(listOf(42L), filled.captions[1].wordTimingsUs)
    }

    @Test
    fun `shift moves all cues and clamps at zero`() {
        val shifted = CaptionEditor.shift(sampleSet(), -500_000L)
        assertEquals(0L, shifted.captions.first().startUs)
        assertEquals(1_500_000L, shifted.captions.first().endUs)
        assertEquals(2_500_000L, shifted.captions[1].startUs)
    }

    @Test
    fun `removeInRange drops overlapping cues only`() {
        val trimmed = CaptionEditor.removeInRange(sampleSet(), 2_500_000L, 3_500_000L)
        assertEquals(1, trimmed.captions.size)
        assertEquals(idA, trimmed.captions.single().id)
    }
}
