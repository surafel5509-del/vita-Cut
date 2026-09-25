package com.vitacut.core.timeline

import com.vitacut.core.model.Interpolation
import com.vitacut.core.model.Keyframe
import com.vitacut.core.model.KeyframeMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyframeAndSnappingTest {

    @Test
    fun `evaluate holds values outside the key range`() {
        val keys = listOf(Keyframe(1_000_000L, 0f), Keyframe(3_000_000L, 1f))
        assertEquals(0f, KeyframeMath.evaluate(keys, 0L), 1e-6f)
        assertEquals(1f, KeyframeMath.evaluate(keys, 9_000_000L), 1e-6f)
    }

    @Test
    fun `linear interpolation is exact at midpoint`() {
        val keys = listOf(Keyframe(0L, 0f), Keyframe(2_000_000L, 10f))
        assertEquals(5f, KeyframeMath.evaluate(keys, 1_000_000L), 1e-6f)
    }

    @Test
    fun `ease in out passes through midpoint with zero slope`() {
        val keys = listOf(
            Keyframe(0L, 0f, Interpolation.EASE_IN_OUT),
            Keyframe(1_000_000L, 1f),
        )
        val mid = KeyframeMath.evaluate(keys, 500_000L)
        assertEquals(0.5f, mid, 1e-6f) // smoothstep(0.5) == 0.5
        assertTrue(KeyframeMath.evaluate(keys, 250_000L) < 0.25f) // slower start
        assertTrue(KeyframeMath.evaluate(keys, 750_000L) > 0.75f) // slower end
    }

    @Test
    fun `hold interpolation keeps previous value`() {
        val keys = listOf(
            Keyframe(0L, 2f, Interpolation.HOLD),
            Keyframe(1_000_000L, 8f),
        )
        assertEquals(2f, KeyframeMath.evaluate(keys, 999_999L), 1e-6f)
        assertEquals(8f, KeyframeMath.evaluate(keys, 1_000_000L), 1e-6f)
    }

    @Test
    fun `single keyframe yields constant`() {
        assertEquals(3f, KeyframeMath.evaluate(listOf(Keyframe(500L, 3f)), 0L), 1e-6f)
        assertEquals(3f, KeyframeMath.evaluate(listOf(Keyframe(500L, 3f)), 9L), 1e-6f)
        assertTrue(KeyframeMath.evaluate(emptyList(), 0L).isNaN().not())
    }

    @Test
    fun `upsert replaces same-timestamp keyframe and keeps order`() {
        val keys = listOf(Keyframe(0L, 0f), Keyframe(2L, 2f))
        val updated = KeyframeMath.upsert(keys, Keyframe(1L, 5f))
        assertEquals(listOf(0L, 1L, 2L), updated.map { it.timeUs })
        val replaced = KeyframeMath.upsert(updated, Keyframe(1L, 9f))
        assertEquals(3, replaced.size)
        assertEquals(9f, replaced[1].value, 1e-6f)
    }

    @Test
    fun `move clamps between neighbors`() {
        val keys = listOf(Keyframe(0L, 0f), Keyframe(5L, 1f), Keyframe(10L, 2f))
        val moved = KeyframeMath.move(keys, 5L, 99L)
        assertEquals(9L, moved[1].timeUs)
        val movedBack = KeyframeMath.move(keys, 5L, -99L)
        assertEquals(1L, movedBack[1].timeUs)
    }

    @Test
    fun `previous and next index navigation`() {
        val keys = listOf(Keyframe(0L, 0f), Keyframe(5L, 1f), Keyframe(10L, 2f))
        assertEquals(1, KeyframeMath.previousIndex(keys, 7L))
        assertEquals(2, KeyframeMath.nextIndex(keys, 7L))
        assertEquals(-1, KeyframeMath.nextIndex(keys, 20L))
    }

    @Test
    fun `color packing round trips`() {
        val argb = 0xFF336699.toInt()
        assertEquals(argb, KeyframeMath.unpackColor(KeyframeMath.packColor(argb)))
    }

    @Test
    fun `snapping grabs nearby anchors on either edge`() {
        // 10 px per second → threshold of 10px == 1s.
        val pixelsPerUs = 10.0 / 1_000_000
        val anchors = listOf(0L, 5_000_000L, 20_000_000L)

        val startSnap = SnappingEngine.snap(
            desiredStartUs = 4_700_000L,
            clipDurationUs = 2_000_000L,
            anchorsUs = anchors,
            pixelsPerUs = pixelsPerUs,
            thresholdPx = 10f,
        )
        assertEquals(5_000_000L, startSnap.positionUs)
        assertEquals(SnappingEngine.Edge.START, startSnap.snappedEdge)

        val endSnap = SnappingEngine.snap(
            desiredStartUs = 18_500_000L,
            clipDurationUs = 1_000_000L,
            anchorsUs = anchors,
            pixelsPerUs = pixelsPerUs,
            thresholdPx = 10f,
        )
        assertEquals(19_000_000L, endSnap.positionUs) // end edge lands on 20s
        assertEquals(SnappingEngine.Edge.END, endSnap.snappedEdge)

        val noSnap = SnappingEngine.snap(
            desiredStartUs = 10_000_000L,
            clipDurationUs = 1_000_000L,
            anchorsUs = anchors,
            pixelsPerUs = pixelsPerUs,
        )
        assertEquals(10_000_000L, noSnap.positionUs)
        assertNull(noSnap.snappedToUs)
    }
}
