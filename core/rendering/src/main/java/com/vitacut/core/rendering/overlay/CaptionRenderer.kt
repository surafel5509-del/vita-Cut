package com.vitacut.core.rendering.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.text.Layout
import com.vitacut.core.model.Caption
import com.vitacut.core.model.CaptionAnimation
import com.vitacut.core.model.CaptionPosition
import com.vitacut.core.model.CaptionSet
import com.vitacut.core.model.CaptionStyle
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Rasterizes caption cues according to [CaptionStyle], including word-level karaoke highlighting
 * when the cue carries [Caption.wordTimingsUs].
 *
 * Positioning: the cue bitmap is rendered at the natural text size; placement is computed by
 * [OverlayComposer] from [CaptionPosition] + offset (bottom/center/top anchoring).
 */
@Singleton
class CaptionRenderer @Inject constructor(
    private val fontRegistry: FontRegistry,
) {

    private var cachedBitmap: Bitmap? = null
    private var cacheKey: Long = 0L

    /**
     * Renders the cue active at [timelineTimeUs]. Returns null when no cue is active or the
     * caption set is disabled/empty.
     */
    fun render(
        captionSet: CaptionSet,
        timelineTimeUs: Long,
        canvasWidth: Int,
        canvasHeight: Int,
    ): RenderedOverlay? {
        if (!captionSet.enabled || captionSet.captions.isEmpty()) return null
        val cue = captionSet.captions.firstOrNull {
            timelineTimeUs in it.startUs until it.endUs
        } ?: return null

        val style = captionSet.style
        val activeWordIndex = if (style.animation == CaptionAnimation.KARAOKE && cue.wordTimingsUs.isNotEmpty()) {
            val relative = timelineTimeUs - cue.startUs
            cue.wordTimingsUs.indexOfLast { it <= relative }
        } else -1

        val key = captionKey(cue, activeWordIndex, style, canvasWidth)
        val bitmap = if (key == cacheKey && cachedBitmap != null) {
            cachedBitmap!!
        } else {
            cachedBitmap?.takeIf { !it.isRecycled }?.recycle()
            rasterize(cue, style, activeWordIndex, canvasWidth, canvasHeight)?.also {
                cachedBitmap = it
                cacheKey = key
            } ?: return null
        }

        // Entrance animation (fade/pop) applied via opacity/scale on placement.
        val cueLocal = timelineTimeUs - cue.startUs
        val animDurationUs = 200_000L
        val animProgress = when (style.animation) {
            CaptionAnimation.NONE -> 1f
            else -> min(1f, cueLocal.toFloat() / animDurationUs)
        }
        val opacity = when (style.animation) {
            CaptionAnimation.FADE, CaptionAnimation.POP -> animProgress
            else -> 1f
        }
        val scale = when (style.animation) {
            CaptionAnimation.POP -> 0.85f + 0.15f * animProgress
            else -> 1f
        }

        val (centerX, centerY) = anchorPosition(style, bitmap, canvasWidth, canvasHeight)
        val placement = OverlayPlacement(
            centerX = centerX,
            centerY = centerY,
            halfWidth = bitmap.width.toFloat() / (2f * canvasWidth) * scale,
            halfHeight = bitmap.height.toFloat() / (2f * canvasHeight) * scale,
            rotationDegrees = 0f,
            opacity = opacity.coerceIn(0f, 1f),
        )
        return RenderedOverlay(bitmap, placement)
    }

    /** NDC center for the configured anchor position. */
    private fun anchorPosition(
        style: CaptionStyle,
        bitmap: Bitmap,
        canvasWidth: Int,
        canvasHeight: Int,
    ): Pair<Float, Float> {
        val halfHeightFraction = bitmap.height.toFloat() / (2f * canvasHeight)
        val y = when (style.position) {
            CaptionPosition.BOTTOM -> -1f + style.offsetFraction * 2f + halfHeightFraction * 2f
            CaptionPosition.TOP -> 1f - style.offsetFraction * 2f - halfHeightFraction * 2f
            CaptionPosition.CENTER -> 0f
        }
        return 0f to y.coerceIn(-1f + halfHeightFraction, 1f - halfHeightFraction)
    }

    private fun rasterize(
        cue: Caption,
        style: CaptionStyle,
        activeWordIndex: Int,
        canvasWidth: Int,
        canvasHeight: Int,
    ): Bitmap? {
        if (cue.text.isBlank()) return null
        val textSizePx = (style.sizeFraction * canvasHeight).coerceAtLeast(12f)
        val strokePx = style.strokeWidthFraction * textSizePx
        val padX = (textSizePx * 0.5f).toInt() + strokePx.toInt() + 4
        val padY = (textSizePx * 0.3f).toInt() + strokePx.toInt() + 4

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = fontRegistry.resolve(style.fontFamilyKey, style.bold, false)
            textSize = textSizePx
            color = style.colorArgb
        }
        val maxWidth = (canvasWidth * 0.86f).toInt().coerceAtLeast(64)
        val layout = StaticLayout.Builder
            .obtain(cue.text, 0, cue.text.length, paint, maxWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.15f)
            .setIncludePad(false)
            .build()

        val backgroundVisible = Color.alpha(style.backgroundColorArgb) > 0
        val bgExtra = if (backgroundVisible) padX / 2 else 0
        val width = layout.width + padX * 2 + bgExtra
        val height = layout.height + padY * 2 + bgExtra

        val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (backgroundVisible) {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.backgroundColorArgb }
            val radius = style.backgroundCornerRadiusFraction * canvasHeight
            canvas.drawRoundRect(
                RectF(0f, 0f, width.toFloat(), height.toFloat()),
                radius, radius, bgPaint,
            )
        }

        canvas.save()
        canvas.translate(padX.toFloat() + bgExtra, padY.toFloat() + bgExtra)

        // Stroke pass.
        if (strokePx > 0.5f) {
            val strokePaint = TextPaint(paint).apply {
                this.style = Paint.Style.STROKE
                strokeWidth = strokePx * 2f
                strokeJoin = Paint.Join.ROUND
                color = style.strokeColorArgb
            }
            val strokeLayout = StaticLayout.Builder
                .obtain(cue.text, 0, cue.text.length, strokePaint, maxWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.15f)
                .setIncludePad(false)
                .build()
            strokeLayout.draw(canvas)
        }

        // Fill pass, with karaoke highlight on the active word.
        if (activeWordIndex >= 0) {
            val words = cue.text.split(" ")
            var charStart = 0
            words.forEachIndexed { index, word ->
                val charEnd = charStart + word.length
                if (index <= activeWordIndex) {
                    val highlightPaint = TextPaint(paint).apply { color = style.highlightColorArgb }
                    // Draw the highlighted prefix over the base layout.
                    val prefixLayout = StaticLayout.Builder
                        .obtain(cue.text, 0, charEnd, highlightPaint, maxWidth)
                        .setAlignment(Layout.Alignment.ALIGN_CENTER)
                        .setLineSpacing(0f, 1.15f)
                        .setIncludePad(false)
                        .build()
                    // Overlay strategy: base text first, then highlighted prefix on top.
                    if (index == min(activeWordIndex, words.lastIndex)) {
                        layout.draw(canvas)
                        prefixLayout.draw(canvas)
                    }
                }
                charStart = charEnd + 1
            }
            if (activeWordIndex < 0) layout.draw(canvas)
        } else {
            layout.draw(canvas)
        }

        canvas.restore()
        return bitmap
    }

    private fun captionKey(
        cue: Caption,
        activeWord: Int,
        style: CaptionStyle,
        canvasWidth: Int,
    ): Long {
        var h = cue.id.value.hashCode().toLong()
        h = h * 31 + activeWord
        h = h * 31 + style.hashCode()
        h = h * 31 + canvasWidth
        h = h * 31 + cue.text.hashCode()
        return h
    }

    fun release() {
        cachedBitmap?.takeIf { !it.isRecycled }?.recycle()
        cachedBitmap = null
        cacheKey = 0L
    }
}
