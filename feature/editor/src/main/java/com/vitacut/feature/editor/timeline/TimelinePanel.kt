package com.vitacut.feature.editor.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitacut.core.common.time.formatTimecode
import com.vitacut.core.designsystem.R
import com.vitacut.core.designsystem.theme.VitaTheme
import com.vitacut.core.media.thumbnails.ThumbnailProvider
import com.vitacut.core.media.waveform.WaveformExtractor
import com.vitacut.core.model.AudioClipItem
import com.vitacut.core.model.ItemId
import com.vitacut.core.model.MediaKind
import com.vitacut.core.model.Project
import com.vitacut.core.model.StickerItem
import com.vitacut.core.model.StickerSource
import com.vitacut.core.model.TextItem
import com.vitacut.core.model.TimelineItem
import com.vitacut.core.model.Track
import com.vitacut.core.model.TrackKind
import com.vitacut.core.model.VideoClipItem
import com.vitacut.core.timeline.SnappingEngine
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The multi-track timeline: pinch-zoomable, snap-aware, thumbnail/waveform-rendered.
 *
 * Rendering strategy: blocks are laid out in absolute pixel space inside one horizontally
 * scrollable surface; strips and waveforms are fetched lazily per zoom bucket and cached, so
 * scrubbing/zooming stays smooth even on PERFORMANCE-mode devices.
 *
 * Drag model: gestures move/trim a *local visual offset* and commit exactly one undoable
 * operation when the finger lifts (no history spam), snapped through [SnappingEngine].
 */
@Composable
fun TimelinePanel(
    project: Project,
    playheadUs: Long,
    selectedItemId: ItemId?,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    onSelect: (ItemId?) -> Unit,
    onMove: (ItemId, Long) -> Unit,
    onTrim: (ItemId, Long, Boolean) -> Unit,
    thumbnailProvider: ThumbnailProvider,
    waveformExtractor: WaveformExtractor,
    modifier: Modifier = Modifier,
) {
    var pxPerSec by rememberSaveable { mutableFloatStateOf(140f) }
    val pxPerUs = pxPerSec / 1_000_000f
    val durationUs = max(project.durationUs, 5_000_000L) + 3_000_000L
    val density = LocalDensity.current

    val scrollState = rememberScrollState()
    val contentWidthPx = with(density) { (durationUs * pxPerUs).toDp().toPx() }

    // Keep the playhead in view while playing.
    LaunchedEffect(isPlaying, playheadUs, pxPerSec) {
        if (isPlaying) {
            val playheadPx = (playheadUs * pxPerUs).toInt()
            val viewport = scrollState.viewportSize
            if (viewport > 0 && (playheadPx < scrollState.value + viewport * 0.25f ||
                    playheadPx > scrollState.value + viewport * 0.8f)
            ) {
                scrollState.scrollTo((playheadPx - viewport * 0.35f).toInt().coerceAtLeast(0))
            }
        }
    }

    val orderedTracks = remember(project.tracks) {
        project.tracks.sortedWith(
            compareBy<Track> { trackOrderWeight(it.kind) }.thenBy { it.order },
        )
    }

    Row(modifier = modifier.background(MaterialTheme.colorScheme.background)) {
        // Track headers.
        Column(
            modifier = Modifier
                .width(36.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Spacer(Modifier.height(24.dp)) // ruler height
            orderedTracks.forEach { track ->
                Box(
                    modifier = Modifier
                        .height(trackHeight(track.kind))
                        .width(36.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        trackIcon(track.kind),
                        contentDescription = stringResource(R.string.a11y_clip, track.kind.name),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(16.dp).height(16.dp),
                    )
                }
            }
        }

        // Scrollable content: ruler + tracks + playhead, pinch-zoom over everything.
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .width(with(density) { contentWidthPx.toDp() })
                    .horizontalScroll(scrollState)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            pxPerSec = (pxPerSec * zoom).coerceIn(16f, 2400f)
                        }
                    },
            ) {
                Ruler(
                    durationUs = durationUs,
                    pxPerUs = pxPerUs,
                    onScrub = { xPx -> onSeek((xPx / pxPerUs).toLong().coerceAtLeast(0L)) },
                )
                orderedTracks.forEach { track ->
                    TrackLane(
                        track = track,
                        project = project,
                        pxPerUs = pxPerUs,
                        playheadUs = playheadUs,
                        selectedItemId = selectedItemId,
                        onSelect = onSelect,
                        onMove = onMove,
                        onTrim = onTrim,
                        thumbnailProvider = thumbnailProvider,
                        waveformExtractor = waveformExtractor,
                    )
                }
            }

            // Playhead overlay (screen-space; accounts for scroll).
            val playheadScreenX = with(density) {
                ((playheadUs * pxPerUs) - scrollState.value).toDp()
            }
            Box(
                modifier = Modifier
                    .offset { IntOffset(playheadScreenX.roundToPx(), 0) }
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(VitaTheme.extended.playhead),
            )
            // Playhead handle for scrubbing.
            Box(
                modifier = Modifier
                    .offset { IntOffset(playheadScreenX.roundToPx() - 6.dp.roundToPx(), 0) }
                    .width(14.dp)
                    .height(24.dp)
                    .pointerInput(playheadUs, pxPerSec) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val newX = playheadScreenX.toPx() + dragAmount.x
                            onSeek(((newX + scrollState.value) / pxPerUs).toLong().coerceAtLeast(0L))
                        }
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(VitaTheme.extended.playhead, radius = size.minDimension / 2.4f)
                }
            }
        }
    }
}

private fun trackOrderWeight(kind: TrackKind): Int = when (kind) {
    TrackKind.VIDEO -> 0
    TrackKind.OVERLAY -> 1
    TrackKind.TEXT -> 2
    TrackKind.STICKER -> 3
    TrackKind.AUDIO -> 4
    TrackKind.VOICEOVER -> 5
}

@Composable
private fun trackHeight(kind: TrackKind) = when (kind) {
    TrackKind.VIDEO, TrackKind.OVERLAY -> 52.dp
    else -> 36.dp
}

@Composable
private fun trackIcon(kind: TrackKind) = when (kind) {
    TrackKind.VIDEO -> Icons.Outlined.Movie
    TrackKind.OVERLAY -> Icons.Outlined.Image
    TrackKind.TEXT -> Icons.Outlined.Title
    TrackKind.STICKER -> Icons.Outlined.Star
    TrackKind.AUDIO -> Icons.Outlined.Audiotrack
    TrackKind.VOICEOVER -> Icons.Outlined.Mic
}

@Composable
private fun Ruler(
    durationUs: Long,
    pxPerUs: Float,
    onScrub: (Float) -> Unit,
) {
    val tickColor = MaterialTheme.colorScheme.outline
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val stepsUs = remember {
        listOf(
            100_000L, 250_000L, 500_000L, 1_000_000L, 2_000_000L, 5_000_000L,
            10_000_000L, 30_000_000L, 60_000_000L, 300_000_000L,
        )
    }
    val stepUs = stepsUs.firstOrNull { it * pxPerUs >= 80f } ?: stepsUs.last()
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(stepUs, pxPerUs, durationUs) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onScrub(change.position.x)
                }
            }
            .pointerInput(stepUs, pxPerUs, durationUs) {
                detectTapGestures { offset -> onScrub(offset.x) }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            var t = 0L
            while (t <= durationUs) {
                val x = t * pxPerUs
                drawLine(
                    color = tickColor,
                    start = Offset(x, size.height),
                    end = Offset(x, size.height - 8.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                )
                t += stepUs
            }
        }
        var labelTime = stepUs
        while (labelTime <= durationUs) {
            val xDp = with(density) { (labelTime * pxPerUs).toDp() }
            Text(
                text = formatTimecode(labelTime, showMillis = false),
                color = textColor,
                fontSize = 8.sp,
                modifier = Modifier.offset(x = xDp + 2.dp).padding(top = 2.dp),
            )
            labelTime += stepUs
        }
    }
}

@Composable
private fun TrackLane(
    track: Track,
    project: Project,
    pxPerUs: Float,
    playheadUs: Long,
    selectedItemId: ItemId?,
    onSelect: (ItemId?) -> Unit,
    onMove: (ItemId, Long) -> Unit,
    onTrim: (ItemId, Long, Boolean) -> Unit,
    thumbnailProvider: ThumbnailProvider,
    waveformExtractor: WaveformExtractor,
) {
    val height = trackHeight(track.kind)
    val anchors = remember(project, playheadUs) {
        buildList {
            add(0L)
            add(playheadUs)
            project.tracks.flatMap { it.items }.forEach {
                add(it.timelineStartUs)
                add(it.timelineEndUs)
            }
        }.distinct()
    }

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .height(height)
            .pointerInput(track.id) {
                detectTapGestures { onSelect(null) }
            },
    ) {
        track.items.sortedBy { it.timelineStartUs }.forEach { item ->
            ItemBlock(
                item = item,
                project = project,
                pxPerUs = pxPerUs,
                laneHeight = height,
                selected = item.id == selectedItemId,
                anchors = anchors,
                onSelect = onSelect,
                onMove = onMove,
                onTrim = onTrim,
                thumbnailProvider = thumbnailProvider,
                waveformExtractor = waveformExtractor,
            )
        }
    }
}

@Composable
private fun ItemBlock(
    item: TimelineItem,
    project: Project,
    pxPerUs: Float,
    laneHeight: androidx.compose.ui.unit.Dp,
    selected: Boolean,
    anchors: List<Long>,
    onSelect: (ItemId?) -> Unit,
    onMove: (ItemId, Long) -> Unit,
    onTrim: (ItemId, Long, Boolean) -> Unit,
    thumbnailProvider: ThumbnailProvider,
    waveformExtractor: WaveformExtractor,
) {
    val density = LocalDensity.current
    var dragOffsetUs by remember(item.id) { mutableLongStateOf(0L) }
    var trimStartUs by remember(item.id) { mutableLongStateOf(0L) }
    var trimEndUs by remember(item.id) { mutableLongStateOf(0L) }

    val visualStart = item.timelineStartUs + dragOffsetUs + trimStartUs
    val visualEnd = item.timelineEndUs + dragOffsetUs - trimEndUs
    val widthUs = max(visualEnd - visualStart, 1L)
    val leftDp = with(density) { (visualStart * pxPerUs).toDp() }
    val widthDp = with(density) { (widthUs * pxPerUs).toDp() }
    val widthPx = with(density) { widthDp.toPx() }

    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    }
    val containerColor = when (item) {
        is VideoClipItem -> MaterialTheme.colorScheme.surfaceVariant
        is AudioClipItem -> MaterialTheme.colorScheme.secondaryContainer
        is TextItem -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)
        is StickerItem -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.25f)
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(leftDp.roundToPx(), 0) }
            .width(widthDp)
            .height(laneHeight - 4.dp)
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(containerColor)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(6.dp),
            )
            .pointerInput(item.id, pxPerUs, anchors) {
                detectTapGestures { onSelect(item.id) }
            }
            .pointerInput(item.id, pxPerUs, anchors) {
                detectDragGesturesAfterLongPress(
                    onDragEnd = {
                        if (dragOffsetUs != 0L) {
                            val desired = item.timelineStartUs + dragOffsetUs
                            val snapped = SnappingEngine.snap(
                                desiredStartUs = desired,
                                clipDurationUs = item.timelineEndUs - item.timelineStartUs,
                                anchorsUs = anchors,
                                pixelsPerUs = pxPerUs.toDouble(),
                            )
                            onMove(item.id, snapped.positionUs)
                        }
                        dragOffsetUs = 0L
                    },
                    onDragCancel = {
                        dragOffsetUs = 0L
                    },
                ) { change, dragAmount ->
                    change.consume()
                    dragOffsetUs += (dragAmount.x / pxPerUs).toLong()
                    dragOffsetUs = max(dragOffsetUs, -item.timelineStartUs)
                }
            },
    ) {
        // Media content of the block.
        when (item) {
            is VideoClipItem -> ClipContent(
                item = item,
                project = project,
                widthPx = widthPx,
                thumbnailProvider = thumbnailProvider,
            )

            is AudioClipItem -> WaveContent(
                item = item,
                project = project,
                widthPx = widthPx,
                waveformExtractor = waveformExtractor,
            )

            is TextItem -> Text(
                text = item.text,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp).align(Alignment.CenterStart),
            )

            is StickerItem -> Text(
                text = when (val source = item.source) {
                    is StickerSource.Emoji -> source.emoji
                    is StickerSource.BuiltIn -> source.stickerKey
                },
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 6.dp).align(Alignment.CenterStart),
            )
        }

        // Trim handles.
        TrimHandle(
            alignment = Alignment.CenterStart,
            pxPerUs = pxPerUs,
            anchors = anchors,
            onTrimDelta = { deltaUs ->
                trimStartUs = (trimStartUs + deltaUs).coerceIn(
                    0L,
                    max(0L, (item.timelineEndUs - item.timelineStartUs) - 100_000L),
                )
            },
            onCommit = {
                if (trimStartUs != 0L) {
                    onTrim(item.id, item.timelineStartUs + trimStartUs, true)
                }
                trimStartUs = 0L
            },
        )
        TrimHandle(
            alignment = Alignment.CenterEnd,
            pxPerUs = pxPerUs,
            anchors = anchors,
            onTrimDelta = { deltaUs ->
                trimEndUs = (trimEndUs - deltaUs).coerceIn(
                    0L,
                    max(0L, (item.timelineEndUs - item.timelineStartUs) - 100_000L),
                )
            },
            onCommit = {
                if (trimEndUs != 0L) {
                    onTrim(item.id, item.timelineEndUs - trimEndUs, false)
                }
                trimEndUs = 0L
            },
        )
    }
}

@Composable
private fun TrimHandle(
    alignment: Alignment,
    pxPerUs: Float,
    anchors: List<Long>,
    onTrimDelta: (Long) -> Unit,
    onCommit: () -> Unit,
) {
    Box(
        modifier = Modifier
            .align(alignment)
            .width(12.dp)
            .fillMaxHeight()
            .pointerInput(pxPerUs, anchors) {
                detectDragGestures(
                    onDragEnd = onCommit,
                    onDragCancel = onCommit,
                ) { change, dragAmount ->
                    change.consume()
                    onTrimDelta((dragAmount.x / pxPerUs).toLong())
                }
            },
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(3.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.7f)),
        )
    }
}

/** Video/image clip content: lazily fetched thumbnail strip scaled to the block width. */
@Composable
private fun ClipContent(
    item: VideoClipItem,
    project: Project,
    widthPx: Float,
    thumbnailProvider: ThumbnailProvider,
) {
    val asset = project.asset(item.assetId)
    var strip by remember(item.id) { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    val cellCount = max(1, (widthPx / 96f).roundToInt())
    val zoomBucket = (widthPx / cellCount).roundToInt()

    LaunchedEffect(item.id, cellCount, zoomBucket, asset?.uri) {
        if (asset == null) return@LaunchedEffect
        strip = if (asset.kind == MediaKind.IMAGE || asset.kind == MediaKind.GIF) {
            listOfNotNull(
                thumbnailProvider.thumbnail(asset.uri, 160, 160)?.asImageBitmap(),
            )
        } else {
            thumbnailProvider
                .strip(
                    uri = asset.uri,
                    startUs = item.sourceInUs,
                    endUs = item.sourceOutUs,
                    targetCount = cellCount,
                    cellWidthPx = 96,
                    cellHeightPx = 96,
                )
                .map { it.asImageBitmap() }
        }
    }

    if (strip.isNotEmpty()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cellWidth = size.width / strip.size
            strip.forEachIndexed { index, bitmap ->
                drawImage(
                    image = bitmap,
                    dstSize = androidx.compose.ui.unit.IntSize(
                        cellWidth.roundToInt().coerceAtLeast(1),
                        size.height.roundToInt().coerceAtLeast(1),
                    ),
                    dstOffset = androidx.compose.ui.unit.IntOffset(
                        (index * cellWidth).roundToInt(),
                        0,
                    ),
                )
            }
        }
    }
    if (item.reversed) {
        Text(
            "◀",
            color = Color.White,
            fontSize = 10.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
        )
    }
}

/** Audio clip content: waveform bars from the cached envelope. */
@Composable
private fun WaveContent(
    item: AudioClipItem,
    project: Project,
    widthPx: Float,
    waveformExtractor: WaveformExtractor,
) {
    val asset = project.asset(item.assetId)
    var peaks by remember(item.id) { mutableStateOf<FloatArray?>(null) }
    val buckets = max(8, (widthPx / 4f).roundToInt()).coerceAtMost(2048)

    LaunchedEffect(item.id, buckets, asset?.uri) {
        if (asset == null) return@LaunchedEffect
        peaks = waveformExtractor.extract(asset.uri, buckets).peaks
    }

    val waveColor = VitaTheme.extended.waveform
    Canvas(modifier = Modifier.fillMaxSize().padding(vertical = 3.dp)) {
        val data = peaks
        if (data == null || data.isEmpty()) return@Canvas
        val barWidth = size.width / data.size
        data.forEachIndexed { index, peak ->
            val h = max(2f, peak * size.height)
            drawRect(
                color = waveColor,
                topLeft = Offset(index * barWidth, (size.height - h) / 2f),
                size = androidx.compose.ui.geometry.Size(
                    max(1f, barWidth - 1f),
                    h,
                ),
            )
        }
    }
    if (item.muted) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
        )
    }
}


