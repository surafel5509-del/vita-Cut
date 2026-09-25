package com.vitacut.core.ai

import com.vitacut.core.ai.beat.AutoCutAnalyzer
import com.vitacut.core.ai.beat.BeatDetector
import com.vitacut.core.ai.reframe.AutoReframeAnalyzer
import com.vitacut.core.ai.silence.SilenceRemover
import com.vitacut.core.ai.tracking.TemplateMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BeatDetectorTest {

    /** 10ms buckets, impulse every 500ms → 120 BPM over 10 seconds. */
    private fun periodicEnvelope(periodBuckets: Int, seconds: Int = 10): FloatArray {
        val peaks = FloatArray(seconds * 100)
        var i = periodBuckets
        while (i < peaks.size) {
            peaks[i] = 0.95f
            peaks[i + 1] = 0.6f
            i += periodBuckets
        }
        return peaks
    }

    @Test
    fun `detects 120 bpm from a 500ms period`() {
        val result = BeatDetector.detect(periodicEnvelope(50), bucketUs = 10_000L)
        assertTrue("expected a tempo, got ${result.bpm}", result.bpm > 0f)
        assertTrue("bpm=${result.bpm}", abs(result.bpm - 120f) < 15f)
        assertTrue(result.confidence > 0.5f)
        // Beat grid spacing ≈ 500ms.
        val spacings = result.beatTimesUs.zipWithNext { a, b -> b - a }
        assertTrue(spacings.isNotEmpty())
        assertTrue(spacings.all { abs(it - 500_000L) < 200_000L })
    }

    @Test
    fun `flat envelope yields no beats`() {
        val result = BeatDetector.detect(FloatArray(1000) { 0.5f }, 10_000L)
        assertEquals(BeatDetector.EMPTY, result)
    }

    @Test
    fun `too few onsets yields no beats`() {
        val peaks = FloatArray(1000)
        peaks[200] = 0.9f
        peaks[700] = 0.9f
        assertTrue(BeatDetector.detect(peaks, 10_000L).beatTimesUs.size < 3)
    }

    @Test
    fun `beatsInRange filters by window`() {
        val result = BeatDetector.detect(periodicEnvelope(50), 10_000L)
        val inWindow = BeatDetector.beatsInRange(result, 1_000_000L, 2_000_000L)
        assertTrue(inWindow.all { it in 1_000_000L..2_000_000L })
        assertTrue(inWindow.size in 2..3)
    }
}

class SilenceRemoverTest {

    @Test
    fun `finds an interior silence and pads edges`() {
        val peaks = FloatArray(1000) { 0.8f } // 10s loud @10ms buckets
        for (i in 400..600) peaks[i] = 0.001f // 4.0s..6.01s silent
        val ranges = SilenceRemover.findSilences(peaks, 10_000L)
        assertEquals(1, ranges.size)
        val silence = ranges.single()
        assertTrue(silence.startUs >= 4_000_000L)
        assertTrue(silence.endUs <= 6_010_000L)
        // Padding trims at least 100ms off each edge.
        assertTrue(silence.startUs > 4_050_000L)
        assertTrue(silence.endUs < 5_960_000L)
    }

    @Test
    fun `short pauses below the minimum are kept`() {
        val peaks = FloatArray(1000) { 0.8f }
        for (i in 400..430) peaks[i] = 0.001f // 300ms
        assertTrue(SilenceRemover.findSilences(peaks, 10_000L).isEmpty())
    }

    @Test
    fun `edge silences are ignored by default`() {
        val peaks = FloatArray(1000) { 0.8f }
        for (i in 0..200) peaks[i] = 0.001f // leading 2s
        for (i in 800..999) peaks[i] = 0.001f // trailing 2s
        val defaultRanges = SilenceRemover.findSilences(peaks, 10_000L)
        assertTrue(defaultRanges.isEmpty())
        val withEdges = SilenceRemover.findSilences(
            peaks,
            10_000L,
            SilenceRemover.Config(ignoreEdges = false),
        )
        assertEquals(2, withEdges.size)
    }

    @Test
    fun `fully silent file is not removable`() {
        assertTrue(SilenceRemover.findSilences(FloatArray(500), 10_000L).isEmpty())
    }

    @Test
    fun `removable duration sums ranges`() {
        val ranges = listOf(
            SilenceRemover.SilenceRange(0L, 1_000_000L),
            SilenceRemover.SilenceRange(2_000_000L, 3_500_000L),
        )
        assertEquals(2_500_000L, SilenceRemover.removableDurationUs(ranges))
    }
}

class AutoCutAnalyzerTest {

    private val beats = BeatDetector.BeatResult(
        beatTimesUs = (1..20).map { it * 500_000L },
        bpm = 120f,
        confidence = 0.9f,
    )

    private val silences = listOf(
        SilenceRemover.SilenceRange(3_000_000L, 5_000_000L),
        SilenceRemover.SilenceRange(7_000_000L, 7_400_000L),
    )

    @Test
    fun `plan snaps cuts to the beat grid`() {
        val plan = AutoCutAnalyzer.plan(beats, silences, durationUs = 10_000_000L)
        assertTrue(plan.cutPointsUs.isNotEmpty())
        // With a 2s target on a 500ms grid every cut should land on a beat.
        plan.cutPointsUs.forEach { cut ->
            assertTrue("cut $cut not on beat grid", beats.beatTimesUs.any { abs(it - cut) < 50_000L })
        }
    }

    @Test
    fun `only long silences are proposed for removal`() {
        val plan = AutoCutAnalyzer.plan(beats, silences, durationUs = 10_000_000L)
        assertEquals(1, plan.removeRanges.size)
        assertEquals(3_000_000L, plan.removeRanges.single().startUs)
    }

    @Test
    fun `highlights are the voiced complement`() {
        val plan = AutoCutAnalyzer.plan(beats, silences, durationUs = 10_000_000L)
        assertTrue(plan.highlightRanges.contains(0L..3_000_000L))
        assertTrue(plan.highlightRanges.contains(5_000_000L..7_000_000L))
        assertTrue(plan.highlightRanges.contains(7_400_000L..10_000_000L))
    }

    @Test
    fun `low-confidence beats fall back to regular cuts`() {
        val noisy = beats.copy(confidence = 0.1f)
        val plan = AutoCutAnalyzer.plan(noisy, emptyList(), durationUs = 10_000_000L)
        assertEquals(listOf(2_000_000L, 4_000_000L, 6_000_000L, 8_000_000L), plan.cutPointsUs)
    }

    @Test
    fun `empty duration yields an empty plan`() {
        assertEquals(AutoCutAnalyzer.CutPlan.EMPTY, AutoCutAnalyzer.plan(beats, silences, 0L))
    }
}

class AutoReframeAnalyzerTest {

    private fun subjects() = listOf(
        AutoReframeAnalyzer.SubjectFrame(0L, 0.2f, 0.5f, confidence = 1f),
        AutoReframeAnalyzer.SubjectFrame(1_000_000L, 0.35f, 0.5f, confidence = 1f),
        AutoReframeAnalyzer.SubjectFrame(2_000_000L, 0.8f, 0.5f, confidence = 1f),
        AutoReframeAnalyzer.SubjectFrame(3_000_000L, 0.9f, 0.5f, confidence = 1f),
    )

    @Test
    fun `portrait target crops the sides`() {
        val result = AutoReframeAnalyzer.analyze(
            subjects(), sourceAspect = 16f / 9f, targetAspect = 9f / 16f, durationUs = 4_000_000L,
        )
        assertTrue(result.cropWidthFraction < 1f)
        assertEquals(1f, result.cropHeightFraction, 0.0001f)
        assertTrue(result.scale > 3f)
    }

    @Test
    fun `path follows the subject but stays in frame`() {
        val result = AutoReframeAnalyzer.analyze(
            subjects(), sourceAspect = 16f / 9f, targetAspect = 9f / 16f, durationUs = 4_000_000L,
        )
        val halfW = result.cropWidthFraction / 2f
        result.path.points.forEach { point ->
            assertTrue("x=${point.x} out of frame", point.x >= halfW - 0.001f && point.x <= 1f - halfW + 0.001f)
        }
        // Ends near the last subject position (rate-limited but converging).
        assertTrue(result.path.points.last().x > 0.6f)
    }

    @Test
    fun `pan speed is rate limited`() {
        val result = AutoReframeAnalyzer.analyze(
            subjects(), sourceAspect = 16f / 9f, targetAspect = 1f, durationUs = 4_000_000L,
            config = AutoReframeAnalyzer.Config(maxPanSpeed = 0.2f),
        )
        val deltas = result.path.points.zipWithNext { a, b ->
            abs(b.x - a.x) / ((b.timeUs - a.timeUs) / 1_000_000f)
        }
        assertTrue(deltas.all { it <= 0.21f })
    }

    @Test
    fun `no subjects yields a centered static path`() {
        val result = AutoReframeAnalyzer.analyze(
            emptyList(), sourceAspect = 16f / 9f, targetAspect = 9f / 16f, durationUs = 5_000_000L,
        )
        assertEquals(1, result.path.points.size)
        assertEquals(0.5f, result.path.points.single().x, 0.0001f)
        assertEquals(0.5f, result.path.points.single().y, 0.0001f)
    }

    @Test
    fun `path is extended to the timeline end and decimated`() {
        val many = (0..200).map {
            AutoReframeAnalyzer.SubjectFrame(it * 50_000L, 0.5f, 0.5f)
        }
        val result = AutoReframeAnalyzer.analyze(many, 16f / 9f, 9f / 16f, 12_000_000L)
        assertTrue(result.path.points.size <= 60)
        assertTrue(result.path.points.last().timeUs >= 10_000_000L)
    }
}

class TemplateMatcherTest {

    /** 32x32 frame with a distinctive 8x8 patch at (px,py). */
    private fun frameWithPatch(px: Int, py: Int, size: Int = 32, patch: Int = 8): ByteArray {
        val gray = ByteArray(size * size) { ((it * 37) % 53).toByte() } // deterministic texture
        for (row in 0 until patch) {
            for (col in 0 until patch) {
                gray[(py + row) * size + px + col] = (200 + (row * patch + col) % 55).toByte()
            }
        }
        return gray
    }

    private fun template(patch: Int = 8): ByteArray {
        val t = ByteArray(patch * patch)
        for (row in 0 until patch) {
            for (col in 0 until patch) {
                t[row * patch + col] = (200 + (row * patch + col) % 55).toByte()
            }
        }
        return t
    }

    @Test
    fun `locates a patch at its exact position`() {
        val frame = frameWithPatch(12, 16)
        val match = TemplateMatcher.locate(template(), 8, 8, frame, 32, 32)
        requireNotNull(match)
        assertTrue("score=${match.score}", match.score > 0.5f)
        // Step-2 search: within one pixel of truth.
        assertTrue(abs(match.x - 12) <= 2)
        assertTrue(abs(match.y - 16) <= 2)
    }

    @Test
    fun `windowed search finds a moved patch near the prior position`() {
        val frame = frameWithPatch(14, 18)
        val match = TemplateMatcher.locate(
            template(), 8, 8, frame, 32, 32,
            searchCenterX = 12, searchCenterY = 16, searchRadius = 6,
        )
        requireNotNull(match)
        assertTrue(match.score > 0.5f)
        assertTrue(abs(match.x - 14) <= 2)
        assertTrue(abs(match.y - 18) <= 2)
    }

    @Test
    fun `flat template cannot match`() {
        val flat = ByteArray(64) { 100 }
        val match = TemplateMatcher.locate(flat, 8, 8, frameWithPatch(4, 4), 32, 32)
        assertTrue(match == null)
    }

    @Test
    fun `toGray converts luma`() {
        val pixels = IntArray(4) { 0xFF804020 } // r=128 g=64 b=32
        val gray = TemplateMatcher.toGray(pixels, 2, 2)
        // 128*77 + 64*150 + 32*29 = 9856+9600+928 = 20384 → shr 8 = 79
        assertEquals(79.toByte(), gray[0])
    }
}
