package com.vitacut.core.timeline

import com.vitacut.core.model.AssetId
import com.vitacut.core.model.AudioEffects
import com.vitacut.core.model.BlendMode
import com.vitacut.core.model.ItemId
import com.vitacut.core.model.MediaAsset
import com.vitacut.core.model.MediaKind
import com.vitacut.core.model.Project
import com.vitacut.core.model.SpeedModel
import com.vitacut.core.model.TrackKind
import com.vitacut.core.model.VideoClipItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineEngineTest {

    private fun videoAsset(durationUs: Long = 10_000_000L) = MediaAsset(
        id = AssetId(),
        uri = "content://media/video/1",
        kind = MediaKind.VIDEO,
        durationUs = durationUs,
        width = 1920,
        height = 1080,
    )

    private fun projectWith(vararg assets: MediaAsset): Project {
        var project = Project.create(name = "Test", nowMs = 0L)
        project = TimelineEngine.addAssets(project, assets.toList())
        for (asset in assets) {
            project = TimelineEngine.appendClip(project, asset)
        }
        return project
    }

    @Test
    fun `appendClip places clips back to back`() {
        val a = videoAsset(10_000_000L)
        val b = videoAsset(5_000_000L)
        val project = projectWith(a, b)
        val clips = project.videoClips()
        assertEquals(2, clips.size)
        assertEquals(0L, clips[0].timelineStartUs)
        assertEquals(10_000_000L, clips[1].timelineStartUs)
        assertEquals(15_000_000L, project.durationUs)
    }

    @Test
    fun `splitItem divides source selection at the playhead`() {
        val asset = videoAsset()
        val project = projectWith(asset)
        val clip = project.videoClips().single()

        val split = TimelineEngine.splitItem(project, clip.id, 4_000_000L)
        val parts = split.videoClips()
        assertEquals(2, parts.size)
        assertEquals(4_000_000L, parts[0].durationUs)
        assertEquals(4_000_000L, parts[0].sourceOutUs)
        assertEquals(4_000_000L, parts[1].timelineStartUs)
        assertEquals(4_000_000L, parts[1].sourceInUs)
        assertEquals(6_000_000L, parts[1].durationUs)
        assertEquals(10_000_000L, parts[1].sourceOutUs)
    }

    @Test
    fun `splitItem outside the clip is a no-op`() {
        val project = projectWith(videoAsset())
        val clip = project.videoClips().single()
        val unchanged = TimelineEngine.splitItem(project, clip.id, 20_000_000L)
        assertEquals(project, unchanged)
    }

    @Test
    fun `trimStart moves start and shrinks duration`() {
        val project = projectWith(videoAsset())
        val clip = project.videoClips().single()
        val trimmed = TimelineEngine.trimStart(project, clip.id, 2_000_000L)
        val result = trimmed.videoClips().single()
        assertEquals(2_000_000L, result.timelineStartUs)
        assertEquals(2_000_000L, result.sourceInUs)
        assertEquals(8_000_000L, result.durationUs)
    }

    @Test
    fun `trimEnd shrinks selection but never below minimum`() {
        val project = projectWith(videoAsset())
        val clip = project.videoClips().single()
        val trimmed = TimelineEngine.trimEnd(project, clip.id, 6_000_000L)
        assertEquals(6_000_000L, trimmed.videoClips().single().durationUs)

        val tiny = TimelineEngine.trimEnd(project, clip.id, 1L)
        assertEquals(TimelineEngine.minDurationUs(), tiny.videoClips().single().durationUs)
    }

    @Test
    fun `moveItem repositions clip and keeps track sorted`() {
        val a = videoAsset(4_000_000L)
        val b = videoAsset(4_000_000L)
        val project = projectWith(a, b)
        val clips = project.videoClips()

        val moved = TimelineEngine.moveItem(project, clips[0].id, 10_000_000L)
        val result = moved.videoClips()
        assertEquals(0L, result[0].timelineStartUs) // clip b stayed put
        assertEquals(10_000_000L, result[1].timelineStartUs)
    }

    @Test
    fun `deleteItem with ripple closes the gap`() {
        val a = videoAsset(3_000_000L)
        val b = videoAsset(3_000_000L)
        val c = videoAsset(3_000_000L)
        val project = projectWith(a, b, c)
        val middle = project.videoClips()[1]

        val deleted = TimelineEngine.deleteItem(project, middle.id, ripple = true)
        val clips = deleted.videoClips()
        assertEquals(2, clips.size)
        assertEquals(0L, clips[0].timelineStartUs)
        assertEquals(3_000_000L, clips[1].timelineStartUs)
        assertEquals(6_000_000L, deleted.durationUs)
    }

    @Test
    fun `duplicateItem copies clip right after the original`() {
        val project = projectWith(videoAsset(5_000_000L))
        val clip = project.videoClips().single()
        val duplicated = TimelineEngine.duplicateItem(project, clip.id)
        val clips = duplicated.videoClips()
        assertEquals(2, clips.size)
        assertEquals(5_000_000L, clips[1].timelineStartUs)
        assertEquals(clip.assetId, clips[1].assetId)
        assertTrue(clips[0].id != clips[1].id)
    }

    @Test
    fun `locked track rejects edits`() {
        val project = projectWith(videoAsset())
        val clip = project.videoClips().single()
        val track = project.trackOfItem(clip.id)!!
        val locked = TimelineEngine.setTrackLocked(project, track.id, locked = true)
        assertEquals(project, TimelineEngine.deleteItem(locked, clip.id))
        assertEquals(locked, TimelineEngine.splitItem(locked, clip.id, 5_000_000L))
    }

    @Test
    fun `detachAudio creates audio item and mutes the clip`() {
        val project = projectWith(videoAsset())
        val clip = project.videoClips().single()
        val detached = TimelineEngine.detachAudio(project, clip.id)

        assertTrue(detached.videoClips().single().muted)
        val audio = detached.audioClips().single()
        assertEquals(clip.timelineStartUs, audio.timelineStartUs)
        assertEquals(clip.sourceInUs, audio.sourceInUs)
        assertEquals(clip.id, audio.detachedFromItemId)
        assertNotNull(detached.tracks.firstOrNull { it.kind == TrackKind.AUDIO })
    }

    @Test
    fun `setSpeed constant recomputes timeline duration`() {
        val project = projectWith(videoAsset(8_000_000L))
        val clip = project.videoClips().single()

        val fast = TimelineEngine.setSpeed(project, clip.id, SpeedModel.Constant(2f))
        assertEquals(4_000_000L, fast.videoClips().single().durationUs)

        val slow = TimelineEngine.setSpeed(project, clip.id, SpeedModel.Constant(0.5f))
        assertEquals(16_000_000L, slow.videoClips().single().durationUs)
    }

    @Test
    fun `freeze frame extends the clip and ripples later items`() {
        val a = videoAsset(4_000_000L)
        val b = videoAsset(4_000_000L)
        val project = projectWith(a, b)
        val first = project.videoClips()[0]

        val frozen = TimelineEngine.addFreezeFrame(project, first.id, 2_000_000L, 1_000_000L)
        val clips = frozen.videoClips()
        assertEquals(5_000_000L, clips[0].durationUs)
        assertEquals(1, clips[0].freezeSegments.size)
        assertEquals(5_000_000L, clips[1].timelineStartUs)
    }

    @Test
    fun `pasteAttributes copies effects filter and grading`() {
        val a = videoAsset()
        val b = videoAsset()
        var project = projectWith(a, b)
        val clips = project.videoClips()
        val effect = com.vitacut.core.model.EffectInstance(
            kind = com.vitacut.core.model.EffectKind.GLITCH,
        )
        project = TimelineEngine.addEffect(project, clips[0].id, effect)
        project = TimelineEngine.setFilter(
            project,
            clips[0].id,
            com.vitacut.core.model.FilterState(filterId = "teal-orange", intensity = 0.8f),
        )

        val pasted = TimelineEngine.pasteAttributes(project, clips[0].id, clips[1].id)
        val target = pasted.videoClips().first { it.id == clips[1].id }
        assertEquals(1, target.effects.size)
        assertEquals(com.vitacut.core.model.EffectKind.GLITCH, target.effects[0].kind)
        assertEquals("teal-orange", target.filter.filterId)
        // Pasted effects get fresh ids so keyframe tracks don't collide.
        assertTrue(target.effects[0].id != effect.id)
    }

    @Test
    fun `undo redo restores every structural edit`() {
        val document = ProjectDocument(projectWith(videoAsset()))
        val clip = document.project.videoClips().single()

        document.update("split") { TimelineEngine.splitItem(it, clip.id, 5_000_000L) }
        assertEquals(2, document.project.videoClips().size)

        document.update("delete") {
            TimelineEngine.deleteItem(it, document.project.videoClips()[0].id)
        }
        assertEquals(1, document.project.videoClips().size)

        assertTrue(document.undo()) // undo delete
        assertEquals(2, document.project.videoClips().size)
        assertTrue(document.undo()) // undo split
        assertEquals(1, document.project.videoClips().size)
        assertTrue(document.redo()) // redo split
        assertEquals(2, document.project.videoClips().size)
        assertTrue(document.redo()) // redo delete
        assertEquals(1, document.project.videoClips().size)
        assertNull(document.project.item(ItemId("nope")))
        assertTrue(!document.redo())
    }

    @Test
    fun `document marks dirty and clears on save`() {
        val document = ProjectDocument(projectWith(videoAsset()))
        assertTrue(!document.dirty.value)
        val clip = document.project.videoClips().single()
        document.update("trim") { TimelineEngine.trimEnd(it, clip.id, 4_000_000L) }
        assertTrue(document.dirty.value)
        document.markSaved()
        assertTrue(!document.dirty.value)
    }

    @Test
    fun `reorderTrack renumbers order values`() {
        var project = projectWith(videoAsset())
        project = TimelineEngine.addTrack(project, TrackKind.AUDIO)
        project = TimelineEngine.addTrack(project, TrackKind.TEXT)
        val textTrack = project.tracks.last()

        val reordered = TimelineEngine.reorderTrack(project, textTrack.id, 0)
        assertEquals(TrackKind.TEXT, reordered.tracks[0].kind)
        assertEquals(listOf(0, 1, 2), reordered.tracks.map { it.order })
    }

    @Test
    fun `insertOverlayClip places a pip clip on an overlay track`() {
        val base = videoAsset()
        val overlayAsset = videoAsset(4_000_000L)
        var project = projectWith(base)
        project = TimelineEngine.addAssets(project, listOf(overlayAsset))
        project = TimelineEngine.insertOverlayClip(project, overlayAsset, atUs = 1_000_000L)
        val overlayTrack = project.tracks.first { it.kind == TrackKind.OVERLAY }
        val pip = overlayTrack.items.filterIsInstance<VideoClipItem>().single()
        assertEquals(1_000_000L, pip.timelineStartUs)
        assertTrue(pip.transform.scaleX < 1f)
        assertEquals(overlayAsset.id, pip.assetId)
    }

    @Test
    fun `rotate and blend updates persist on the clip`() {
        val project = projectWith(videoAsset())
        val clip = project.videoClips().single()
        val rotated = TimelineEngine.rotateItem(project, clip.id, 90f)
        assertEquals(90f, (rotated.item(clip.id) as VideoClipItem).transform.rotationDegrees, 0.01f)
        val blended = TimelineEngine.setBlendMode(rotated, clip.id, BlendMode.SCREEN)
        assertEquals(BlendMode.SCREEN, (blended.item(clip.id) as VideoClipItem).blendMode)
    }

    @Test
    fun `audio effects attach to audio clips`() {
        val asset = MediaAsset(
            uri = "content://media/audio/1",
            kind = MediaKind.AUDIO,
            durationUs = 5_000_000L,
        )
        var project = Project.create(name = "Audio", nowMs = 0L)
        project = TimelineEngine.addAssets(project, listOf(asset))
        val item = com.vitacut.core.model.AudioClipItem(
            assetId = asset.id,
            sourceOutUs = 5_000_000L,
            durationUs = 5_000_000L,
        )
        project = TimelineEngine.insertItem(project, null, TrackKind.AUDIO, item)
        val updated = TimelineEngine.setAudioEffects(
            project,
            item.id,
            AudioEffects(bass = 0.4f, reverb = 0.5f, voiceEnhance = true),
        )
        val audio = updated.audioClips().single()
        assertEquals(0.4f, audio.audioEffects.bass, 0.001f)
        assertTrue(audio.audioEffects.voiceEnhance)
    }
}
