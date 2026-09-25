package com.vitacut.core.export

import com.vitacut.core.model.AssetId
import com.vitacut.core.model.AspectRatio
import com.vitacut.core.model.CanvasSettings
import com.vitacut.core.model.ExportAudioBitrate
import com.vitacut.core.model.ExportBitrateMode
import com.vitacut.core.model.ExportFrameRate
import com.vitacut.core.model.ExportResolution
import com.vitacut.core.model.ExportSettings
import com.vitacut.core.model.ExportVideoCodec
import com.vitacut.core.model.Project
import com.vitacut.core.model.Track
import com.vitacut.core.model.TrackKind
import com.vitacut.core.model.VideoClipItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPlannerTest {

    private fun project(
        durationUs: Long = 10_000_000L,
        aspect: AspectRatio = AspectRatio.RATIO_16_9,
    ): Project = Project(
        name = "Test",
        canvas = CanvasSettings(aspectRatio = aspect),
        tracks = listOf(
            Track(
                kind = TrackKind.VIDEO,
                items = listOf(
                    VideoClipItem(
                        assetId = AssetId("asset-1"),
                        timelineStartUs = 0L,
                        sourceInUs = 0L,
                        sourceOutUs = durationUs,
                        durationUs = durationUs,
                    ),
                ),
            ),
        ),
    )

    private fun planOf(
        settings: ExportSettings = ExportSettings.DEFAULT,
        project: Project = project(),
        caps: CapabilitySnapshot = CapabilitySnapshot.UNRESTRICTED,
    ): ExportPlan = when (val validation = ExportPlanner.plan(settings, project, caps)) {
        is ExportValidation.Ready -> validation.plan
        is ExportValidation.Invalid -> error("Expected a plan, got invalid: ${validation.messageKey}")
    }

    @Test
    fun `empty timeline is rejected`() {
        val validation = ExportPlanner.plan(
            ExportSettings.DEFAULT,
            Project(name = "Empty"),
            CapabilitySnapshot.UNRESTRICTED,
        )
        assertTrue(validation is ExportValidation.Invalid)
        assertEquals("export_error_empty_timeline", (validation as ExportValidation.Invalid).messageKey)
    }

    @Test
    fun `unrestricted device keeps requested settings`() {
        val settings = ExportSettings(
            resolution = ExportResolution.P4K,
            frameRate = ExportFrameRate.FPS_60,
            videoCodec = ExportVideoCodec.HEVC,
        )
        val plan = planOf(settings)
        assertEquals(ExportVideoCodec.HEVC, plan.settings.videoCodec)
        assertEquals(ExportResolution.P4K, plan.settings.resolution)
        assertEquals(ExportFrameRate.FPS_60, plan.settings.frameRate)
        assertTrue(plan.fallbacks.isEmpty())
        assertEquals(3840, plan.outputWidth)
        assertEquals(2160, plan.outputHeight)
    }

    @Test
    fun `hevc degrades to h264 on low-end devices`() {
        val plan = planOf(
            ExportSettings(videoCodec = ExportVideoCodec.HEVC),
            caps = CapabilitySnapshot.LOW_END,
        )
        assertEquals(ExportVideoCodec.H264, plan.settings.videoCodec)
        assertTrue(plan.fallbacks.any { it.messageKey == "export_fallback_hevc" })
    }

    @Test
    fun `4k degrades to 1080p when encoder caps forbid it`() {
        val plan = planOf(
            ExportSettings(resolution = ExportResolution.P4K),
            caps = CapabilitySnapshot.LOW_END,
        )
        assertEquals(ExportResolution.P1080, plan.settings.resolution)
        assertEquals(1920, plan.outputWidth)
        assertEquals(1080, plan.outputHeight)
        assertTrue(plan.fallbacks.any { it.messageKey == "export_fallback_resolution" })
    }

    @Test
    fun `60fps degrades to 30fps when unsupported`() {
        val plan = planOf(
            ExportSettings(frameRate = ExportFrameRate.FPS_60),
            caps = CapabilitySnapshot.LOW_END,
        )
        assertEquals(ExportFrameRate.FPS_30, plan.settings.frameRate)
        assertTrue(plan.fallbacks.any { it.messageKey == "export_fallback_fps" })
    }

    @Test
    fun `portrait canvas maps 1080p to width`() {
        val plan = planOf(
            ExportSettings(resolution = ExportResolution.P1080),
            project = project(aspect = AspectRatio.RATIO_9_16),
        )
        assertEquals(1080, plan.outputWidth)
        assertEquals(1920, plan.outputHeight)
    }

    @Test
    fun `square canvas produces even dimensions`() {
        val plan = planOf(
            ExportSettings(resolution = ExportResolution.P720),
            project = project(aspect = AspectRatio.RATIO_1_1),
        )
        assertEquals(0, plan.outputWidth % 2)
        assertEquals(0, plan.outputHeight % 2)
        assertEquals(plan.outputWidth, plan.outputHeight)
    }

    @Test
    fun `custom bitrate is clamped into sane bounds`() {
        val low = planOf(
            ExportSettings(bitrateMode = ExportBitrateMode.CUSTOM, customBitrateKbps = 10),
        )
        assertTrue(low.videoBitrateBps >= 500_000)
        val high = planOf(
            ExportSettings(bitrateMode = ExportBitrateMode.CUSTOM, customBitrateKbps = 1_000_000),
        )
        assertTrue(high.videoBitrateBps <= 120_000_000)
    }

    @Test
    fun `audio bitrate honors the selection`() {
        val plan = planOf(ExportSettings(audioBitrate = ExportAudioBitrate.KBPS_320))
        assertEquals(320_000, plan.audioBitrateBps)
    }

    @Test
    fun `estimated output size matches bitrate times duration`() {
        val plan = planOf(ExportSettings.DEFAULT, project(durationUs = 60_000_000L))
        val estimated = ExportPlanner.estimatedOutputBytes(plan, 60_000_000L)
        val expected = (plan.videoBitrateBps + plan.audioBitrateBps) / 8.0 * 60.0
        assertEquals(expected.toLong(), estimated)
        assertTrue(estimated > 0)
    }

    @Test
    fun `bitrate mode tiers are ordered`() {
        val low = planOf(ExportSettings(bitrateMode = ExportBitrateMode.LOW))
        val medium = planOf(ExportSettings(bitrateMode = ExportBitrateMode.MEDIUM))
        val high = planOf(ExportSettings(bitrateMode = ExportBitrateMode.HIGH))
        assertTrue(low.videoBitrateBps < medium.videoBitrateBps)
        assertTrue(medium.videoBitrateBps < high.videoBitrateBps)
    }
}
