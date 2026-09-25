package com.vitacut.domain.usecase.edit

import com.vitacut.core.model.AssetId
import com.vitacut.core.model.Project
import com.vitacut.core.model.Track
import com.vitacut.core.model.TrackKind
import com.vitacut.core.model.VideoClipItem
import com.vitacut.core.timeline.ProjectDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiEditUseCasesTest {

    private fun tenSecondProject(): Project = Project(
        name = "Ten",
        tracks = listOf(
            Track(
                kind = TrackKind.VIDEO,
                items = listOf(
                    VideoClipItem(
                        assetId = AssetId("a"),
                        timelineStartUs = 0L,
                        sourceInUs = 0L,
                        sourceOutUs = 10_000_000L,
                        durationUs = 10_000_000L,
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `silence removal closes the gap and is one undo step`() {
        val document = ProjectDocument(tenSecondProject())
        ApplySilenceRemovalUseCase()(document, listOf(3_000_000L..4_999_999L))

        val project = document.project
        assertEquals(8_000_000L, project.durationUs)
        val items = project.tracks.single().items
        // Two pieces remain: 0..3s and (rippled) 3..8s.
        assertEquals(2, items.size)
        assertEquals(0L, items.minOf { it.timelineStartUs })
        assertEquals(8_000_000L, items.maxOf { it.timelineEndUs })

        assertTrue(document.historyState.value.canUndo)
        document.undo()
        assertEquals(10_000_000L, document.project.durationUs)
    }

    @Test
    fun `auto cut splits on beat points then removes silences`() {
        val document = ProjectDocument(tenSecondProject())
        ApplyAutoCutUseCase()(
            document,
            cutPointsUs = listOf(2_000_000L),
            removeRanges = listOf(4_000_000L..5_999_999L),
        )
        val project = document.project
        assertEquals(8_000_000L, project.durationUs)
        val items = project.tracks.single().items.sortedBy { it.timelineStartUs }
        // 0..2 (beat split), 2..4, then tail 6..10 rippled to 4..8.
        assertEquals(3, items.size)
        assertEquals(listOf(0L, 2_000_000L, 4_000_000L), items.map { it.timelineStartUs })
        assertEquals(listOf(2_000_000L, 4_000_000L, 8_000_000L), items.map { it.timelineEndUs })
    }

    @Test
    fun `removing multiple ranges keeps coordinates stable (descending application)`() {
        val document = ProjectDocument(tenSecondProject())
        ApplySilenceRemovalUseCase()(
            document,
            listOf(1_000_000L..1_999_999L, 6_000_000L..6_999_999L),
        )
        assertEquals(8_000_000L, document.project.durationUs)
    }

    @Test
    fun `reframe path becomes keyframes on the item`() {
        val document = ProjectDocument(tenSecondProject())
        val itemId = document.project.tracks.single().items.single().id
        val path = com.vitacut.core.model.TrackPath(
            points = listOf(
                com.vitacut.core.model.TrackPoint(0L, 0.25f, 0.5f),
                com.vitacut.core.model.TrackPoint(1_000_000L, 0.75f, 0.5f),
            ),
        )
        ApplyAutoReframeUseCase()(document, itemId, path, scale = 3.16f)

        val item = document.project.tracks.single().items.single()
        val set = when (item) {
            is VideoClipItem -> item.keyframes
            else -> error("expected video item")
        }
        val posX = set.trackFor(com.vitacut.core.model.KeyframeProperty.POSITION_X)
        val posY = set.trackFor(com.vitacut.core.model.KeyframeProperty.POSITION_Y)
        val scaleX = set.trackFor(com.vitacut.core.model.KeyframeProperty.SCALE_X)
        requireNotNull(posX)
        requireNotNull(posY)
        requireNotNull(scaleX)
        assertEquals(2, posX.keyframes.size)
        assertEquals(0.5f, posX.keyframes[0].value, 0.0001f) // (0.5-0.25)*2
        assertEquals(-0.5f, posX.keyframes[1].value, 0.0001f) // (0.5-0.75)*2
        assertEquals(0f, posY.keyframes[0].value, 0.0001f) // (0.5-0.5)*2
        assertEquals(3.16f, scaleX.keyframes.single().value, 0.0001f)
    }

    @Test
    fun `empty reframe path is a no-op`() {
        val document = ProjectDocument(tenSecondProject())
        val itemId = document.project.tracks.single().items.single().id
        ApplyAutoReframeUseCase()(document, itemId, com.vitacut.core.model.TrackPath(), 1f)
        assertTrue(!document.historyState.value.canUndo)
    }
}
