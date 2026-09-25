package com.vitacut.core.timeline

import kotlin.math.abs

/**
 * Magnetic snapping for timeline dragging.
 *
 * Candidate anchors are collected by the caller (playhead position, clip edges of all visible
 * tracks, timeline zero, marker positions). Snapping works in *pixels*: an anchor grabs the
 * dragged edge when it is within [thresholdPx] at the current zoom, which keeps the feel
 * consistent at any pixels-per-second scale.
 */
object SnappingEngine {

    /** Result of a snap attempt: adjusted position + the anchor it snapped to (for UI feedback). */
    data class SnapResult(val positionUs: Long, val snappedToUs: Long?, val snappedEdge: Edge?)

    enum class Edge { START, END }

    /**
     * @param desiredStartUs where the dragged clip *would* start without snapping
     * @param clipDurationUs duration of the dragged clip
     * @param anchorsUs all snap anchors in timeline coordinates
     * @param pixelsPerUs current zoom scale
     * @param thresholdPx grab distance in pixels (typically 8-12dp)
     */
    fun snap(
        desiredStartUs: Long,
        clipDurationUs: Long,
        anchorsUs: List<Long>,
        pixelsPerUs: Double,
        thresholdPx: Float = 10f,
    ): SnapResult {
        if (anchorsUs.isEmpty() || pixelsPerUs <= 0.0) {
            return SnapResult(desiredStartUs, null, null)
        }
        val thresholdUs = (thresholdPx / pixelsPerUs).toLong()
        val desiredEndUs = desiredStartUs + clipDurationUs

        var bestAnchor: Long? = null
        var bestEdge: Edge? = null
        var bestDelta = Long.MAX_VALUE

        for (anchor in anchorsUs) {
            val startDelta = anchor - desiredStartUs
            if (abs(startDelta) <= thresholdUs && abs(startDelta) < abs(bestDelta)) {
                bestDelta = startDelta
                bestAnchor = anchor
                bestEdge = Edge.START
            }
            val endDelta = anchor - desiredEndUs
            if (abs(endDelta) <= thresholdUs && abs(endDelta) < abs(bestDelta)) {
                bestDelta = endDelta
                bestAnchor = anchor
                bestEdge = Edge.END
            }
        }

        return if (bestAnchor == null || bestEdge == null) {
            SnapResult(desiredStartUs, null, null)
        } else {
            val adjusted = when (bestEdge) {
                Edge.START -> desiredStartUs + bestDelta
                Edge.END -> desiredStartUs + bestDelta
            }
            SnapResult(adjusted.coerceAtLeast(0L), bestAnchor, bestEdge)
        }
    }

    /**
     * Collects snap anchors from a project at [timeUs]: playhead, zero, and every clip edge on
     * every non-hidden track (excluding the item currently being dragged).
     */
    fun anchorsFor(
        tracks: List<com.vitacut.core.model.Track>,
        playheadUs: Long,
        excludeItemId: String? = null,
    ): List<Long> {
        val anchors = mutableListOf(0L, playheadUs)
        for (track in tracks) {
            if (track.hidden) continue
            for (item in track.items) {
                if (item.id.value == excludeItemId) continue
                anchors += item.timelineStartUs
                anchors += item.timelineEndUs
            }
        }
        return anchors.distinct().sorted()
    }
}
