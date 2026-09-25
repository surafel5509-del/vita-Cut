package com.vitacut.core.ai.tracking

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Grayscale normalized-cross-correlation template matcher — the compute core of basic motion
 * tracking. Pure Kotlin over primitive arrays so it is unit-testable and has no platform
 * dependency; [MotionTracker] feeds it frames from `MediaMetadataRetriever`.
 *
 * NCC is used (instead of SSD) because it is invariant to global brightness changes, which are
 * constant between adjacent video frames of the same shot.
 */
object TemplateMatcher {

    data class Match(
        /** Top-left of the matched region in frame pixels. */
        val x: Int,
        val y: Int,
        /** NCC score in -1..1; > 0.5 is a confident match. */
        val score: Float,
    )

    /** Converts packed ARGB pixels to grayscale bytes (Rec. 709 luma). */
    fun toGray(pixels: IntArray, width: Int, height: Int): ByteArray {
        val gray = ByteArray(width * height)
        for (i in 0 until width * height) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            gray[i] = ((r * 77 + g * 150 + b * 29) shr 8).toByte()
        }
        return gray
    }

    /** Extracts a template window from a grayscale frame. */
    fun crop(gray: ByteArray, frameWidth: Int, x: Int, y: Int, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h)
        for (row in 0 until h) {
            val src = (y + row) * frameWidth + x
            System.arraycopy(gray, src, out, row * w, w)
        }
        return out
    }

    /**
     * Finds the best match for [template] (tw×th) inside [frame] (fw×fh).
     *
     * @param searchCenterX/Y optional prior position (template top-left); when provided the
     *   search is limited to ±[searchRadius] pixels around it (2× faster, rejects teleporting
     *   false positives). Step of 2 pixels balances speed vs. precision for video rates.
     * @return null when the template does not fit the frame.
     */
    fun locate(
        template: ByteArray,
        tw: Int,
        th: Int,
        frame: ByteArray,
        fw: Int,
        fh: Int,
        searchCenterX: Int? = null,
        searchCenterY: Int? = null,
        searchRadius: Int = Int.MAX_VALUE,
    ): Match? {
        if (tw <= 0 || th <= 0 || tw > fw || th > fh) return null

        val tMean = template.average().toFloat()
        val tNorm = norm(template, tMean)
        if (tNorm < 1e-4f) return null // flat template: nothing to match

        var best = Match(0, 0, -2f)
        val fromX: Int
        val toX: Int
        val fromY: Int
        val toY: Int
        if (searchCenterX != null && searchCenterY != null && searchRadius != Int.MAX_VALUE) {
            fromX = max(0, searchCenterX - searchRadius)
            toX = min(fw - tw, searchCenterX + searchRadius)
            fromY = max(0, searchCenterY - searchRadius)
            toY = min(fh - th, searchCenterY + searchRadius)
        } else {
            fromX = 0; toX = fw - tw; fromY = 0; toY = fh - th
        }

        var y = fromY
        while (y <= toY) {
            var x = fromX
            while (x <= toX) {
                val score = ncc(template, tMean, tNorm, tw, th, frame, fw, x, y)
                if (score > best.score) best = Match(x, y, score)
                x += 2
            }
            y += 2
        }
        return best.takeIf { it.score > -2f }
    }

    private fun ncc(
        template: ByteArray,
        tMean: Float,
        tNorm: Float,
        tw: Int,
        th: Int,
        frame: ByteArray,
        fw: Int,
        ox: Int,
        oy: Int,
    ): Float {
        // Window mean.
        var sum = 0L
        for (row in 0 until th) {
            val base = (oy + row) * fw + ox
            for (col in 0 until tw) sum += frame[base + col]
        }
        val wMean = sum.toFloat() / (tw * th)
        var cross = 0f
        var wSq = 0f
        for (row in 0 until th) {
            val fBase = (oy + row) * fw + ox
            val tBase = row * tw
            for (col in 0 until tw) {
                val f = frame[fBase + col] - wMean
                val t = template[tBase + col] - tMean
                cross += f * t
                wSq += f * f
            }
        }
        val wNorm = sqrt(wSq)
        if (wNorm < 1e-4f) return -1f
        return cross / (tNorm * wNorm)
    }

    private fun norm(template: ByteArray, mean: Float): Float {
        var sq = 0f
        for (v in template) {
            val d = v - mean
            sq += d * d
        }
        return sqrt(sq)
    }
}
