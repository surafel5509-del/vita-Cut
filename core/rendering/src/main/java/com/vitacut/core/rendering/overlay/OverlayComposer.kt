package com.vitacut.core.rendering.overlay

import android.graphics.Bitmap
import com.vitacut.core.model.ItemId
import com.vitacut.core.model.MediaKind
import com.vitacut.core.model.Project
import com.vitacut.core.model.StickerItem
import com.vitacut.core.model.TextItem
import com.vitacut.core.model.TrackKind
import com.vitacut.core.model.VideoClipItem
import com.vitacut.core.rendering.effects.OverlayLayerFrame
import com.vitacut.core.rendering.effects.OverlayLayerProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns the project's overlay layers (text, stickers, image overlays, captions) into
 * [OverlayLayerProvider]s bound to a specific clip window.
 *
 * Providers speak *clip-local* time (what the GL pipeline delivers) and convert internally to
 * absolute timeline time, so the same providers work for:
 * - preview (ExoPlayer.setVideoEffects, item-local timestamps), and
 * - export (Transformer EditedMediaItem effects, item-local timestamps).
 *
 * Image overlay bitmaps are supplied by an [ImageFrameSource] (implemented by the media layer
 * with cached decoders) and prefetched eagerly so the GL thread never blocks on IO.
 */
@Singleton
class OverlayComposer @Inject constructor(
    private val textRenderer: TextLayerRenderer,
    private val stickerRenderer: StickerRenderer,
    private val captionRenderer: CaptionRenderer,
) {

    /** Supplies a decoded bitmap for an image overlay URI (implementations must be cached). */
    fun interface ImageFrameSource {
        /** May return null while loading or on failure — the layer is skipped then. */
        fun bitmapFor(uri: String, targetWidth: Int): Bitmap?
    }

    data class OverlayLayer(
        val itemId: ItemId,
        val zOrder: Int,
        val provider: OverlayLayerProvider,
    )

    /**
     * Builds overlay layers visible during [windowStartUs]..[windowEndUs), ordered back-to-front.
     * Captions (when enabled) render above media overlays but below text/stickers.
     */
    fun layersForWindow(
        project: Project,
        windowStartUs: Long,
        windowEndUs: Long,
        imageFrameSource: ImageFrameSource?,
    ): List<OverlayLayer> {
        val canvasWidth = project.canvas.width
        val canvasHeight = project.canvas.height
        val layers = mutableListOf<OverlayLayer>()
        var z = 0

        // Image overlays on OVERLAY tracks.
        for (track in project.tracks.filter { it.kind == TrackKind.OVERLAY && !it.hidden }.sortedBy { it.order }) {
            for (item in track.items.filter { it.timelineStartUs < windowEndUs && it.timelineEndUs > windowStartUs }
                .sortedBy { it.timelineStartUs }) {
                when (item) {
                    is VideoClipItem -> {
                        val asset = project.asset(item.assetId)
                        if (asset != null && (asset.kind == MediaKind.IMAGE || asset.kind == MediaKind.GIF) &&
                            imageFrameSource != null
                        ) {
                            val uri = asset.playbackUri(useProxy = false)
                            val layer = item
                            layers += OverlayLayer(
                                itemId = item.id,
                                zOrder = z++,
                                provider = OverlayLayerProvider { localTimeUs ->
                                    val timelineTime = windowStartUs + localTimeUs
                                    if (timelineTime !in layer.timelineStartUs until layer.timelineEndUs) return@OverlayLayerProvider null
                                    val bitmap = imageFrameSource.bitmapFor(uri, canvasWidth / 2)
                                        ?: return@OverlayLayerProvider null
                                    val placement = OverlayPlacement.fromTransform(
                                        transform = layer.transform,
                                        bitmapWidth = bitmap.width,
                                        bitmapHeight = bitmap.height,
                                        canvasWidth = canvasWidth,
                                        canvasHeight = canvasHeight,
                                        blendMode = layer.blendMode,
                                    )
                                    OverlayLayerFrame(bitmap, placement)
                                },
                            )
                        }
                    }

                    is StickerItem -> layers += stickerLayer(project, item, windowStartUs, canvasWidth, canvasHeight, z++)
                    is TextItem -> layers += textLayer(item, windowStartUs, canvasWidth, canvasHeight, z++)
                    else -> Unit
                }
            }
        }

        // Captions.
        if (project.captions.enabled && project.captions.captions.isNotEmpty()) {
            val hasCueInWindow = project.captions.captions.any {
                it.startUs < windowEndUs && it.endUs > windowStartUs
            }
            if (hasCueInWindow) {
                layers += OverlayLayer(
                    itemId = ItemId("captions"),
                    zOrder = z++,
                    provider = OverlayLayerProvider { localTimeUs ->
                        captionRenderer.render(
                            project.captions,
                            windowStartUs + localTimeUs,
                            canvasWidth,
                            canvasHeight,
                        )?.let { OverlayLayerFrame(it.bitmap, it.placement) }
                    },
                )
            }
        }

        // Text & sticker tracks (above captions).
        for (track in project.tracks
            .filter { it.kind == TrackKind.TEXT || it.kind == TrackKind.STICKER }
            .filter { !it.hidden }
            .sortedBy { it.order }) {
            for (item in track.items.filter { it.timelineStartUs < windowEndUs && it.timelineEndUs > windowStartUs }
                .sortedBy { it.timelineStartUs }) {
                when (item) {
                    is TextItem -> layers += textLayer(item, windowStartUs, canvasWidth, canvasHeight, z++)
                    is StickerItem ->
                        layers += stickerLayer(project, item, windowStartUs, canvasWidth, canvasHeight, z++)

                    else -> Unit
                }
            }
        }

        return layers.sortedBy { it.zOrder }
    }

    private fun textLayer(
        item: TextItem,
        windowStartUs: Long,
        canvasWidth: Int,
        canvasHeight: Int,
        z: Int,
    ): OverlayLayer = OverlayLayer(
        itemId = item.id,
        zOrder = z,
        provider = OverlayLayerProvider { localTimeUs ->
            textRenderer.render(item, windowStartUs + localTimeUs, canvasWidth, canvasHeight)
                ?.let { OverlayLayerFrame(it.bitmap, it.placement) }
        },
    )

    private fun stickerLayer(
        project: Project,
        item: StickerItem,
        windowStartUs: Long,
        canvasWidth: Int,
        canvasHeight: Int,
        z: Int,
    ): OverlayLayer {
        val trackPath = item.trackingBindingId?.let { project.trackingPaths[it] }
        return OverlayLayer(
            itemId = item.id,
            zOrder = z,
            provider = OverlayLayerProvider { localTimeUs ->
                stickerRenderer.render(
                    item,
                    windowStartUs + localTimeUs,
                    canvasWidth,
                    canvasHeight,
                    trackPath,
                )?.let { OverlayLayerFrame(it.bitmap, it.placement) }
            },
        )
    }

    fun releaseCaches() {
        textRenderer.release()
        captionRenderer.release()
        stickerRenderer.clearCaches()
    }
}
