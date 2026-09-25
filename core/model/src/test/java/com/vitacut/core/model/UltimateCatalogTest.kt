package com.vitacut.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UltimateCatalogTest {

    @Test
    fun `effect library covers every ultimate category`() {
        assertTrue(EffectKind.entries.size >= 40)
        assertTrue(EffectCategory.entries.toSet().containsAll(EffectKind.entries.map { it.category }.toSet()))
        EffectCategory.entries.forEach { category ->
            assertTrue("$category is empty", EffectKind.entries.any { it.category == category })
        }
    }

    @Test
    fun `transition library includes creative kinds`() {
        assertTrue(TransitionKind.entries.size >= 30)
        assertNotNull(TransitionKind.IRIS)
        assertNotNull(TransitionKind.WHIP_PAN)
        assertTrue(TransitionKind.PIXELATE_OUT.category == TransitionCategory.CREATIVE)
    }

    @Test
    fun `text style presets are unique and complete`() {
        assertTrue(TextStyleLibrary.ALL.size >= 12)
        assertEquals(TextStyleLibrary.ALL.size, TextStyleLibrary.ALL.map { it.id }.toSet().size)
        assertNotNull(TextStyleLibrary.byId("neon-cyan"))
        assertNotNull(TextStyleLibrary.byId("cinematic-gold"))
        assertTrue(TextAnimationIn.entries.size >= 12)
        assertTrue(TextAnimationOut.entries.size >= 7)
        assertTrue(TextAnimationLoop.entries.size >= 8)
    }

    @Test
    fun `filter categories include ultimate families`() {
        assertTrue(FilterCategory.entries.contains(FilterCategory.MOODY))
        assertTrue(FilterCategory.entries.contains(FilterCategory.FILM))
        assertTrue(FilterCategory.entries.contains(FilterCategory.STREET))
        assertTrue(FilterCategory.entries.contains(FilterCategory.FANTASY))
    }

    @Test
    fun `project json round trip preserves ultimate effect and transition`() {
        val assetId = AssetId()
        val clip = VideoClipItem(
            assetId = assetId,
            timelineStartUs = 0L,
            sourceOutUs = 3_000_000L,
            durationUs = 3_000_000L,
            effects = listOf(
                EffectInstance(kind = EffectKind.BLOOM, intensity = 0.7f),
                EffectInstance(kind = EffectKind.RAIN, intensity = 0.4f),
            ),
            transitionOut = TransitionState(TransitionKind.IRIS, durationUs = 400_000L),
        )
        val project = Project(
            name = "Ultimate",
            assets = mapOf(
                assetId.value to MediaAsset(
                    id = assetId,
                    uri = "content://media/video/9",
                    kind = MediaKind.VIDEO,
                    durationUs = 3_000_000L,
                    width = 1080,
                    height = 1920,
                ),
            ),
            tracks = listOf(Track(kind = TrackKind.VIDEO, items = listOf(clip))),
        )
        val restored = projectFromJson(project.toJson())
        assertNotNull(restored)
        val restoredClip = restored!!.videoClips().single()
        assertEquals(listOf(EffectKind.BLOOM, EffectKind.RAIN), restoredClip.effects.map { it.kind })
        assertEquals(TransitionKind.IRIS, restoredClip.transitionOut?.kind)
    }

    @Test
    fun `template json with creative transitions parses`() {
        val json = """
            {
              "id": "bundled-test",
              "nameKey": "template_reel_quick",
              "category": "REELS",
              "slots": [
                { "order": 0, "kind": "MEDIA", "durationUs": 1000000, "transitionOut": "WHIP_PAN" },
                { "order": 1, "kind": "TEXT", "durationUs": 1000000, "textKey": "template_text_title_default" }
              ]
            }
        """.trimIndent()
        val template = templateFromJson(json)
        assertNotNull(template)
        assertEquals(TemplateCategory.REELS, template!!.category)
        assertEquals(TransitionKind.WHIP_PAN, template.slots[0].transitionOut)
        assertEquals(2, template.slots.size)
    }
}
