package com.vitacut.core.timeline

import com.vitacut.core.model.SpeedCurve
import com.vitacut.core.model.SpeedCurvePoint
import com.vitacut.core.model.SpeedCurvePreset
import com.vitacut.core.model.SpeedModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SpeedMathTest {

    @Test
    fun `constant speed duration is exact`() {
        assertEquals(
            5_000_000L,
            SpeedMath.timelineDurationUs(0L, 10_000_000L, SpeedModel.Constant(2f)),
        )
        assertEquals(
            20_000_000L,
            SpeedMath.timelineDurationUs(0L, 10_000_000L, SpeedModel.Constant(0.5f)),
        )
    }

    @Test
    fun `uniform curve matches constant speed`() {
        val curve = SpeedCurve(listOf(SpeedCurvePoint(0f, 2f), SpeedCurvePoint(1f, 2f)))
        val duration = SpeedMath.timelineDurationUs(0L, 10_000_000L, SpeedModel.Curve(curve))
        assertTrue(abs(duration - 5_000_000L) < 1_000L) // integration error << 1 frame
    }

    @Test
    fun `source mapping is monotonic and hits endpoints`() {
        val curve = SpeedCurve.forPreset(SpeedCurvePreset.BULLET)
        val timeline = SpeedMath.timelineDurationUs(0L, 10_000_000L, SpeedModel.Curve(curve))
        val first = SpeedMath.sourceTimeAt(0L, timeline, 0L, 10_000_000L, SpeedModel.Curve(curve))
        val last = SpeedMath.sourceTimeAt(timeline, timeline, 0L, 10_000_000L, SpeedModel.Curve(curve))
        val middle = SpeedMath.sourceTimeAt(timeline / 2, timeline, 0L, 10_000_000L, SpeedModel.Curve(curve))
        assertEquals(0L, first)
        assertEquals(10_000_000L, last)
        assertTrue(middle in 1..9_999_999L)

        var previous = -1L
        for (step in 0..100) {
            val t = timeline * step / 100
            val source = SpeedMath.sourceTimeAt(t, timeline, 0L, 10_000_000L, SpeedModel.Curve(curve))
            assertTrue("source time must never go backwards", source >= previous)
            previous = source
        }
    }

    @Test
    fun `bullet time curve slows the middle of the clip`() {
        val curve = SpeedCurve.forPreset(SpeedCurvePreset.BULLET)
        assertEquals(1f, curve.speedAt(0f), 1e-6f)
        assertEquals(0.25f, curve.speedAt(0.5f), 1e-6f)
        assertEquals(1f, curve.speedAt(1f), 1e-6f)

        val timeline = SpeedMath.timelineDurationUs(0L, 10_000_000L, SpeedModel.Curve(curve))
        // With a slow middle section the timeline duration must exceed 10s.
        assertTrue(timeline > 10_000_000L)
    }

    @Test
    fun `speed curve point editing keeps endpoints`() {
        val curve = SpeedCurve.forPreset(SpeedCurvePreset.MONTAGE)
        val withExtra = curve.addPoint(SpeedCurvePoint(0.5f, 1.5f))
        assertEquals(0f, withExtra.points.first().position, 1e-6f)
        assertEquals(1f, withExtra.points.last().position, 1e-6f)

        val removed = withExtra.removePoint(2)
        assertTrue(removed.points.size >= 2)
        // Removing can never drop the first or last point.
        assertEquals(0f, removed.points.first().position, 1e-6f)
        assertEquals(1f, removed.points.last().position, 1e-6f)
    }

    @Test
    fun `degenerate curve never crashes`() {
        val empty = SpeedCurve(points = emptyList())
        assertEquals(1f, empty.speedAt(0.3f), 1e-6f)
        val duration = SpeedMath.timelineDurationUs(0L, 1_000_000L, SpeedModel.Curve(empty))
        assertEquals(1_000_000L, duration)
    }
}
