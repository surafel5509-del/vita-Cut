package com.vitacut.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectSerializationTest {

    private fun sampleProject(): Project {
        val assetId = AssetId()
        val asset = MediaAsset(
            id = assetId,
            uri = "content://media/external/video/media/42",
            kind = MediaKind.VIDEO,
            mimeType = "video/mp4",
            displayName = "clip.mp4",
            durationUs = 12_345_678L,
            width = 3840,
            height = 2160,
            rotationDegrees = 90,
            frameRate = 59.94f,
        )
        val clip = VideoClipItem(
            assetId = assetId,
            timelineStartUs = 0L,
            sourceInUs = 1_000_000L,
            sourceOutUs = 6_000_000L,
            durationUs = 5_000_000L,
            speed = SpeedModel.Curve(SpeedCurve.forPreset(SpeedCurvePreset.HERO), SpeedCurvePreset.HERO),
            effects = listOf(EffectInstance(kind = EffectKind.VHS, intensity = 0.6f)),
            filter = FilterState("cinema-01", 0.75f),
            grading = Grading(
                adjustments = ColorAdjustments(contrast = 0.2f, temperature = -0.1f, grain = 0.3f),
                curves = ToneCurves(rgb = listOf(CurvePoint(0f, 0.05f), CurvePoint(0.5f, 0.45f), CurvePoint(1f, 1f))),
                hsl = HslAdjustments(mapOf(HslBand.ORANGE.name to HslBandAdjustment(saturation = 0.2f))),
            ),
            mask = MaskSettings(shape = MaskShape.CIRCLE, feather = 0.35f),
            chromaKey = ChromaKeySettings(enabled = true, background = KeyBackground.SolidColor(0xFF101010.toInt())),
            transitionOut = TransitionState(TransitionKind.GLITCH_OUT, durationUs = 300_000L),
            keyframes = KeyframeSet(
                listOf(
                    KeyframeTrack(
                        KeyframeProperty.OPACITY,
                        listOf(Keyframe(0L, 0f), Keyframe(500_000L, 1f, Interpolation.EASE_OUT)),
                    ),
                ),
            ),
        )
        val text = TextItem(
            text = "Create. Edit. Inspire.",
            timelineStartUs = 500_000L,
            durationUs = 3_000_000L,
            style = TextStyle(bold = true, gradientStartArgb = 0xFFFFD54F.toInt(), gradientEndArgb = 0xFFE91E63.toInt()),
            animations = TextAnimations(inAnimation = TextAnimationIn.TYPEWRITER),
        )
        val sticker = StickerItem(
            source = StickerSource.Emoji("🎬"),
            timelineStartUs = 0L,
            durationUs = 2_000_000L,
        )
        val audio = AudioClipItem(
            assetId = AssetId(),
            timelineStartUs = 0L,
            sourceInUs = 30_000_000L,
            sourceOutUs = 35_000_000L,
            durationUs = 5_000_000L,
            audioEffects = AudioEffects(bass = 0.3f, reverb = 0.2f, voiceEnhance = true),
        )
        return Project(
            name = "Roundtrip",
            createdAtMs = 1L,
            updatedAtMs = 2L,
            canvas = CanvasSettings(aspectRatio = AspectRatio.RATIO_9_16, background = CanvasBackground.BlurFill),
            assets = mapOf(assetId.value to asset),
            tracks = listOf(
                Track(kind = TrackKind.VIDEO, items = listOf(clip)),
                Track(kind = TrackKind.TEXT, items = listOf(text)),
                Track(kind = TrackKind.STICKER, items = listOf(sticker)),
                Track(kind = TrackKind.AUDIO, items = listOf(audio)),
            ),
            captions = CaptionSet(
                captions = listOf(Caption(startUs = 0L, endUs = 2_000_000L, text = "Hello world")),
                languageTag = "am",
            ),
            trackingPaths = mapOf("bind-1" to TrackPath(listOf(TrackPoint(0L, 0.1f, 0.2f), TrackPoint(1L, 0.3f, 0.4f)))),
        )
    }

    @Test
    fun `project json round trip preserves every element`() {
        val original = sampleProject()
        val json = original.toJson()
        val restored = projectFromJson(json)

        assertNotNull(restored)
        assertEquals(original, restored)

        val clip = restored!!.videoClips().single()
        assertTrue(clip.speed is SpeedModel.Curve)
        assertEquals(MaskShape.CIRCLE, clip.mask.shape)
        assertEquals(0xFFFFD54F.toInt(), restored.textItems().single().style.gradientStartArgb)
        assertEquals(2, clip.keyframes.tracks.single().keyframes.size)
        assertEquals(Interpolation.EASE_OUT, clip.keyframes.tracks.single().keyframes[1].interpolation)
        assertEquals(1, clip.effects.size)
        assertEquals(KeyBackground.SolidColor(0xFF101010.toInt()), clip.chromaKey.background)
        assertEquals(1, restored.captions.captions.size)
        assertEquals("am", restored.captions.languageTag)
    }

    @Test
    fun `unknown fields are ignored (forward compatibility)`() {
        val original = sampleProject()
        val json = original.toJson()
            .replaceFirst("\"name\":\"Roundtrip\"", "\"name\":\"Roundtrip\",\"futureField\":123")
        val restored = projectFromJson(json)
        assertNotNull(restored)
        assertEquals("Roundtrip", restored!!.name)
    }

    @Test
    fun `garbage input returns null instead of throwing`() {
        assertNull(projectFromJson("not json at all"))
        assertNull(projectFromJson("{}"))
        assertNull(projectFromJson(""))
    }

    @Test
    fun `srt and vtt export formats are correct`() {
        val captions = listOf(
            Caption(startUs = 0L, endUs = 1_500_000L, text = "First line"),
            Caption(startUs = 2_000_000L, endUs = 4_000_000L, text = "Second\nmulti-line"),
        )
        val srt = CaptionFormats.toSrt(captions)
        assertTrue(srt.contains("1\n00:00:00,000 --> 00:00:01,500\nFirst line"))
        assertTrue(srt.contains("2\n00:00:02,000 --> 00:00:04,000\nSecond\nmulti-line"))

        val vtt = CaptionFormats.toVtt(captions)
        assertTrue(vtt.startsWith("WEBVTT"))
        assertTrue(vtt.contains("00:00:00,000 --> 00:00:01,500"))
    }

    @Test
    fun `srt parser reads back what the writer produced`() {
        val captions = listOf(
            Caption(startUs = 0L, endUs = 1_500_000L, text = "First line"),
            Caption(startUs = 2_000_000L, endUs = 4_000_000L, text = "Second"),
        )
        val parsed = CaptionFormats.parseSrt(CaptionFormats.toSrt(captions))
        assertEquals(2, parsed.size)
        assertEquals(0L, parsed[0].startUs)
        assertEquals(1_500_000L, parsed[0].endUs)
        assertEquals("First line", parsed[0].text)
        assertEquals("Second", parsed[1].text)
    }

    @Test
    fun `tone curve evaluation clamps and interpolates`() {
        val points = listOf(CurvePoint(0f, 0.2f), CurvePoint(0.5f, 0.5f), CurvePoint(1f, 0.9f))
        assertEquals(0.2f, ToneCurves.evaluate(points, -1f), 1e-6f)
        assertEquals(0.9f, ToneCurves.evaluate(points, 2f), 1e-6f)
        assertEquals(0.5f, ToneCurves.evaluate(points, 0.5f), 1e-6f)
        val mid = ToneCurves.evaluate(points, 0.25f)
        assertTrue(mid in 0.2f..0.5f)
    }

    @Test
    fun `duration and resolution labels`() {
        val project = sampleProject()
        assertEquals(1080, project.canvas.width)
        assertEquals(1920, project.canvas.height)
        assertEquals("1080x1920", project.resolutionLabel)
        assertEquals(5_000_000L, project.durationUs)
    }
}
