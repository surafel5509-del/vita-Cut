package com.vitacut.core.captions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSegmenterTest {

    /** 10ms buckets: 100 buckets = 1 second. */
    private val bucketUs = 10_000L

    private fun envelope(vararg runs: Pair<Int, Float>): FloatArray {
        val peaks = FloatArray(1000) // 10 seconds
        for ((startBucket, level) in runs) {
            for (i in startBucket until startBucket + 100) { // 1s each
                if (i < peaks.size) peaks[i] = level
            }
        }
        return peaks
    }

    @Test
    fun `empty input yields no segments`() {
        assertTrue(SpeechSegmenter.segment(FloatArray(0), bucketUs).isEmpty())
        assertTrue(SpeechSegmenter.segment(FloatArray(10) { 0.5f }, 0L).isEmpty())
    }

    @Test
    fun `silence yields no segments`() {
        val peaks = FloatArray(1000) { 0.001f }
        assertTrue(SpeechSegmenter.segment(peaks, bucketUs).isEmpty())
    }

    @Test
    fun `two loud runs produce two segments`() {
        val peaks = envelope(100 to 0.9f, 500 to 0.8f)
        val segments = SpeechSegmenter.segment(peaks, bucketUs)
        assertEquals(2, segments.size)
        // Segment 1 around bucket 100..200 → 1.0s..2.0s (±pad)
        assertTrue(segments[0].startUs in 800_000L..1_100_000L)
        assertTrue(segments[0].endUs in 1_900_000L..2_200_000L)
        // Segment 2 around 5.0s..6.0s
        assertTrue(segments[1].startUs in 4_800_000L..5_100_000L)
        assertTrue(segments[1].endUs in 5_900_000L..6_200_000L)
    }

    @Test
    fun `short gaps inside speech are merged`() {
        val peaks = FloatArray(1000)
        // Loud from bucket 100..300 with a 5-bucket (50ms) dip in the middle.
        for (i in 100..300) peaks[i] = if (i in 195..199) 0.05f else 0.9f
        val segments = SpeechSegmenter.segment(peaks, bucketUs)
        assertEquals(1, segments.size)
        assertTrue(segments[0].durationUs >= 1_900_000L)
    }

    @Test
    fun `blips shorter than minimum are dropped`() {
        val peaks = FloatArray(1000)
        for (i in 200..210) peaks[i] = 0.9f // 110ms blip
        for (i in 600..750) peaks[i] = 0.9f // 1.5s real speech
        val segments = SpeechSegmenter.segment(peaks, bucketUs)
        assertEquals(1, segments.size)
        assertTrue(segments[0].startUs in 5_800_000L..6_100_000L)
    }

    @Test
    fun `over-long runs are split near the quietest interior point`() {
        val peaks = FloatArray(1500) // 15s
        for (i in 100..1400) peaks[i] = 0.9f
        // Deliberate quiet dips for split snapping.
        for (i in 540..560) peaks[i] = 0.10f
        for (i in 940..960) peaks[i] = 0.10f
        val segments = SpeechSegmenter.segment(
            peaks,
            bucketUs,
            SpeechSegmenter.Config(maxSegmentUs = 5_000_000L),
        )
        assertTrue("expected multiple chunks, got ${segments.size}", segments.size >= 3)
        segments.forEach { assertTrue(it.durationUs <= 5_500_000L) }
        // No segment may extend past the voiced region (+pad).
        assertTrue(segments.last().endUs <= 14_200_000L)
    }

    @Test
    fun `segments are padded and clamped to the envelope`() {
        val peaks = FloatArray(1000)
        for (i in 0..150) peaks[i] = 0.9f // speech starts at file start
        val segments = SpeechSegmenter.segment(peaks, bucketUs)
        assertEquals(1, segments.size)
        assertEquals(0L, segments[0].startUs) // clamped, not negative
        assertTrue(segments[0].endUs > 1_500_000L) // padded past the run
    }
}
