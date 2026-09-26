package com.vitacut.core.rendering.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.vitacut.core.model.TextAnimationIn
import com.vitacut.core.model.TextAnimationLoop
import com.vitacut.core.model.TextAnimationOut
import com.vitacut.core.model.TextAlignment
import com.vitacut.core.model.TextItem
import com.vitacut.core.model.KeyframeMath
import com.vitacut.core.model.KeyframeProperty
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A rendered text frame: bitmap + where it goes + how visible it is. */
data class RenderedOverlay(
    val bitmap: Bitmap,
    val placement: OverlayPlacement,
)

/**
 * Rasterizes [TextItem]s with the full styling model: font, size, bold/italic/underline,
 * alignment, letter & line spacing, solid or gradient fill, stroke, shadow, rounded background
 * and opacity — plus in/out/loop animations and keyframes.
 *
 * The *same* renderer feeds:
 * - export (bitmap → OverlayCompositeEffect),
 * - preview (bitmap → same effect on the player chain), and
 * - the editor's static thumbnail in the text panel.
 * which is what guarantees WYSIWYG text between preview and exported video.
 *
 * Bitmaps are cached per (content-hash, quantized animation state) to avoid re-rasterizing
 * every frame while the layer is static.
 */
@Singleton
class TextLayerRenderer @Inject constructor(
    private val fontRegistry: FontRegistry,
) {

    private var cachedBitmap: Bitmap? = null
    private var cacheKey: Long = 0L

    /**
     * Renders [item] for the absolute timeline position [timelineTimeUs] against the canvas size.
     * Returns null when the item is invisible at this time (outside its window or fully faded).
     */
    fun render(
        item: TextItem,
        timelineTimeUs: Long,
        canvasWidth: Int,
        canvasHeight: Int,
    ): RenderedOverlay? {
        val local = timelineTimeUs - item.timelineStartUs
        if (local < 0 || local >= item.durationUs) return null

        val anim = evaluateAnimation(item, local)
        if (anim.opacity <= 0.002f) return null

        // Effective style after keyframes.
        val sizeFraction = keyframed(item, KeyframeProperty.TEXT_SIZE, local, item.style.sizeFraction)
        val letterSpacing = keyframed(item, KeyframeProperty.LETTER_SPACING, local, item.style.letterSpacing)
        val lineSpacing = keyframed(item, KeyframeProperty.LINE_SPACING, local, item.style.lineSpacing)
        val colorArgb = keyframedColor(item, KeyframeProperty.TEXT_COLOR, local, item.style.colorArgb)

        val text = if (anim.visibleTextLength < item.text.length) {
            item.text.take(anim.visibleTextLength)
        } else {
            item.text
        }
        if (text.isEmpty()) return null

        val textSizePx = max(8f, sizeFraction * canvasHeight * anim.scale)
        val key = contentKey(item, text, textSizePx, letterSpacing, lineSpacing, colorArgb, canvasWidth)

        val bitmap = if (key == cacheKey && cachedBitmap != null) {
            cachedBitmap!!
        } else {
            cachedBitmap?.takeIf { !it.isRecycled }?.recycle()
            rasterize(item, text, textSizePx, letterSpacing, lineSpacing, colorArgb, canvasWidth, canvasHeight)
                .also {
                    cachedBitmap = it
                    cacheKey = key
                } ?: return null
        }

        val transform = item.transform
        val placement = OverlayPlacement.fromTransform(
            transform = transform,
            bitmapWidth = bitmap.width,
            bitmapHeight = bitmap.height,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            opacityOverride = anim.opacity * keyframed(item, KeyframeProperty.OPACITY, local, transform.opacity),
            scaleOverride = 1f, // scale already applied through textSizePx; keep bitmap 1:1
            translationOverrideX = transform.translationX + anim.offsetX,
            translationOverrideY = transform.translationY + anim.offsetY,
            rotationOverride = transform.rotationDegrees + anim.rotationDegrees,
        )
        return RenderedOverlay(bitmap, placement)
    }

    private data class AnimationState(
        val opacity: Float,
        val scale: Float,
        val offsetX: Float, // NDC units
        val offsetY: Float,
        val rotationDegrees: Float,
        val visibleTextLength: Int,
    )

    private fun evaluateAnimation(item: TextItem, localUs: Long): AnimationState {
        var opacity = 1f
        var scale = 1f
        var offsetX = 0f
        var offsetY = 0f
        var rotation = 0f
        var visibleLength = item.text.length

        val inDur = item.animations.inDurationUs.coerceAtLeast(1L)
        if (localUs < inDur) {
            val p = (localUs.toFloat() / inDur).coerceIn(0f, 1f)
            when (item.animations.inAnimation) {
                TextAnimationIn.NONE -> Unit
                TextAnimationIn.FADE -> opacity = p
                TextAnimationIn.ZOOM -> {
                    opacity = p
                    scale = 0.4f + 0.6f * KeyframeMath.smoothStep(p)
                }
                TextAnimationIn.SLIDE -> {
                    opacity = min(1f, p * 2f)
                    offsetY = -(1f - KeyframeMath.smoothStep(p)) * 0.4f
                }
                TextAnimationIn.BOUNCE -> {
                    opacity = min(1f, p * 3f)
                    val overshoot = if (p < 0.6f) p / 0.6f * 1.15f else 1.15f - (p - 0.6f) / 0.4f * 0.15f
                    scale = 0.3f + 0.7f * overshoot
                }
                TextAnimationIn.TYPEWRITER -> {
                    visibleLength = (item.text.length * p).toInt()
                }
                TextAnimationIn.POP -> {
                    opacity = min(1f, p * 2f)
                    scale = 0.8f + 0.4f * sin(p * Math.PI.toFloat())
                }
                TextAnimationIn.GLITCH -> {
                    opacity = min(1f, p * 3f)
                    offsetX = (hash(p * 17f) - 0.5f) * 0.08f * (1f - p)
                    offsetY = (hash(p * 29f) - 0.5f) * 0.04f * (1f - p)
                }
                TextAnimationIn.WAVE -> {
                    opacity = min(1f, p * 2f)
                    offsetY = sin(p * Math.PI.toFloat() * 2f) * 0.12f * (1f - p)
                }
                TextAnimationIn.FLIP -> {
                    opacity = min(1f, p * 2f)
                    scale = abs(cos((1f - p) * Math.PI.toFloat()))
                }
                TextAnimationIn.SLIDE_UP -> {
                    opacity = min(1f, p * 2f)
                    offsetY = -(1f - KeyframeMath.smoothStep(p)) * 0.55f
                }
                TextAnimationIn.SLIDE_DOWN -> {
                    opacity = min(1f, p * 2f)
                    offsetY = (1f - KeyframeMath.smoothStep(p)) * 0.55f
                }
                TextAnimationIn.ROTATE -> {
                    opacity = min(1f, p * 2f)
                    rotation = (1f - KeyframeMath.smoothStep(p)) * -28f
                    scale = 0.7f + 0.3f * p
                }
                TextAnimationIn.NEON -> {
                    opacity = min(1f, p * 1.6f)
                    scale = 0.92f + 0.08f * sin(p * Math.PI.toFloat() * 3f)
                }
            }
        }

        val outDur = item.animations.outDurationUs.coerceAtLeast(1L)
        val outStart = item.durationUs - outDur
        if (item.durationUs > outDur && localUs > outStart) {
            val p = ((localUs - outStart).toFloat() / outDur).coerceIn(0f, 1f)
            when (item.animations.outAnimation) {
                TextAnimationOut.NONE -> Unit
                TextAnimationOut.FADE -> opacity *= (1f - p)
                TextAnimationOut.SLIDE -> {
                    opacity *= (1f - p)
                    offsetY += p * 0.4f
                }
                TextAnimationOut.ZOOM -> {
                    opacity *= (1f - p)
                    scale *= 1f + p * 0.8f
                }
                TextAnimationOut.BLUR -> {
                    opacity *= (1f - p * p)
                    scale *= 1f - 0.1f * p
                }
                TextAnimationOut.POP -> {
                    opacity *= (1f - p)
                    scale *= 1f + 0.4f * p
                }
                TextAnimationOut.GLITCH -> {
                    opacity *= (1f - p)
                    offsetX += (hash(p * 41f) - 0.5f) * 0.1f
                }
                TextAnimationOut.SPIN -> {
                    opacity *= (1f - p)
                    rotation += p * 90f
                    scale *= 1f - 0.25f * p
                }
            }
        }

        val loopStart = inDur
        val loopEnd = if (item.durationUs > outDur) outStart else item.durationUs
        if (localUs in loopStart..loopEnd) {
            val t = (localUs - loopStart) / 1_000_000f
            when (item.animations.loopAnimation) {
                TextAnimationLoop.NONE -> Unit
                TextAnimationLoop.PULSE -> scale *= 1f + 0.06f * sin(t * 4f)
                TextAnimationLoop.BOUNCE -> offsetY += 0.05f * abs(sin(t * 3f))
                TextAnimationLoop.SHAKE -> offsetX += 0.01f * sin(t * 40f) * sin(t * 7f)
                TextAnimationLoop.FLOATING -> {
                    offsetY += 0.03f * sin(t * 1.7f)
                    offsetX += 0.015f * sin(t * 1.1f)
                }
                TextAnimationLoop.WAVE -> offsetY += 0.04f * sin(t * 5f)
                TextAnimationLoop.GLITCH -> {
                    if (sin(t * 13f) > 0.85f) {
                        offsetX += (hash(t) - 0.5f) * 0.06f
                    }
                }
                TextAnimationLoop.NEON -> opacity *= 0.82f + 0.18f * abs(sin(t * 6f))
                TextAnimationLoop.WIGGLE -> {
                    rotation += sin(t * 8f) * 4f
                    offsetX += sin(t * 6f) * 0.012f
                }
            }
        }

        return AnimationState(
            opacity.coerceIn(0f, 1f),
            scale,
            offsetX,
            offsetY,
            rotation,
            visibleLength,
        )
    }

    private fun hash(n: Float): Float {
        val x = sin(n * 12.9898f) * 43758.5453f
        return x - floor(x)
    }

    private fun rasterize(
        item: TextItem,
        text: String,
        textSizePx: Float,
        letterSpacing: Float,
        lineSpacing: Float,
        colorArgb: Int,
        canvasWidth: Int,
        canvasHeight: Int,
    ): Bitmap? {
        val style = item.style
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = fontRegistry.resolve(style.fontFamilyKey, style.bold, style.italic)
            this.textSize = textSizePx
            isUnderlineText = style.underline
            this.color = colorArgb
            if (Build.VERSION.SDK_INT >= 28) {
                this.letterSpacing = letterSpacing
            }
        }

        val maxTextWidth = (canvasWidth * 0.9f).toInt().coerceAtLeast(64)
        val alignment = when (style.alignment) {
            TextAlignment.START -> Layout.Alignment.ALIGN_NORMAL
            TextAlignment.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlignment.END -> Layout.Alignment.ALIGN_OPPOSITE
        }
        val layout = if (Build.VERSION.SDK_INT >= 23) {
            StaticLayout.Builder
                .obtain(text, 0, text.length, paint, maxTextWidth)
                .setAlignment(alignment)
                .setLineSpacing(0f, lineSpacing.coerceIn(0.5f, 3f))
                .setIncludePad(false)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(
                text, paint, maxTextWidth, alignment,
                lineSpacing.coerceIn(0.5f, 3f), 0f, false,
            )
        }

        val padX = (style.backgroundPaddingFraction * canvasHeight).toInt()
        val padY = (style.backgroundPaddingFraction * canvasHeight * 0.6f).toInt()
        val strokePx = style.strokeWidthFraction * textSizePx
        val shadowPx = style.shadowRadiusFraction * textSizePx
        val extra = max(strokePx, shadowPx * 2f).toInt() + max(padX, padY)

        val width = (layout.width + extra * 2).coerceAtLeast(1)
        val height = (layout.height + extra * 2).coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Rounded background.
        if (Color.alpha(style.backgroundColorArgb) > 0) {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.backgroundColorArgb }
            val radius = style.backgroundCornerRadiusFraction * canvasHeight
            canvas.drawRoundRect(
                RectF(0f, 0f, width.toFloat(), height.toFloat()),
                radius, radius, bgPaint,
            )
        }

        canvas.save()
        canvas.translate(extra.toFloat(), extra.toFloat())

        val textLeft = (maxTextWidth - layout.width) / 2f +
            when (style.alignment) {
                TextAlignment.START -> -(maxTextWidth - layout.width) / 2f
                TextAlignment.CENTER -> 0f
                TextAlignment.END -> (maxTextWidth - layout.width) / 2f
            }

        // Shadow pass.
        if (shadowPx > 0.5f) {
            paint.setShadowLayer(
                shadowPx,
                style.shadowDxFraction * textSizePx,
                style.shadowDyFraction * textSizePx,
                style.shadowColorArgb,
            )
        }

        // Gradient fill overrides the solid color when configured.
        val gradientStart = style.gradientStartArgb
        val gradientEnd = style.gradientEndArgb
        if (gradientStart != null && gradientEnd != null) {
            paint.shader = LinearGradient(
                0f, 0f, layout.width.toFloat(), layout.height.toFloat(),
                gradientStart, gradientEnd,
                Shader.TileMode.CLAMP,
            )
        }

        canvas.save()
        canvas.translate(textLeft, 0f)
        layout.draw(canvas)
        canvas.restore()

        // Stroke pass on top (behind-fill would need two layouts; over-stroke reads cleaner).
        if (strokePx > 0.5f) {
            val strokePaint = TextPaint(paint).apply {
                this.style = Paint.Style.STROKE
                this.strokeWidth = strokePx * 2f
                this.strokeJoin = Paint.Join.ROUND
                color = style.strokeColorArgb
                shader = null
                clearShadowLayer()
            }
            val strokeLayout = buildStrokeLayout(text, strokePaint, maxTextWidth, alignment, lineSpacing)
            canvas.save()
            canvas.translate(textLeft, 0f)
            strokeLayout.draw(canvas)
            canvas.restore()
        }

        paint.clearShadowLayer()
        canvas.restore()
        return bitmap
    }

    private fun buildStrokeLayout(
        text: String,
        paint: TextPaint,
        maxWidth: Int,
        alignment: Layout.Alignment,
        lineSpacing: Float,
    ): StaticLayout = if (Build.VERSION.SDK_INT >= 23) {
        StaticLayout.Builder
            .obtain(text, 0, text.length, paint, maxWidth)
            .setAlignment(alignment)
            .setLineSpacing(0f, lineSpacing.coerceIn(0.5f, 3f))
            .setIncludePad(false)
            .build()
    } else {
        @Suppress("DEPRECATION")
        StaticLayout(text, paint, maxWidth, alignment, lineSpacing.coerceIn(0.5f, 3f), 0f, false)
    }

    // region Keyframe helpers

    private fun keyframed(item: TextItem, property: KeyframeProperty, localUs: Long, base: Float): Float {
        val track = item.keyframes.trackFor(property) ?: return base
        if (track.keyframes.isEmpty()) return base
        return KeyframeMath.evaluate(track.keyframes, localUs)
    }

    private fun keyframedColor(item: TextItem, property: KeyframeProperty, localUs: Long, base: Int): Int {
        val track = item.keyframes.trackFor(property) ?: return base
        if (track.keyframes.isEmpty()) return base
        // Colors keyframe in ARGB-int space via bit-packed floats.
        val bits = KeyframeMath.evaluate(track.keyframes, localUs)
        return KeyframeMath.unpackColor(bits)
    }

    private fun contentKey(
        item: TextItem,
        text: String,
        textSizePx: Float,
        letterSpacing: Float,
        lineSpacing: Float,
        colorArgb: Int,
        canvasWidth: Int,
    ): Long {
        var h = item.id.value.hashCode().toLong()
        h = h * 31 + text.hashCode()
        h = h * 31 + textSizePx.toRawBits()
        h = h * 31 + letterSpacing.toRawBits()
        h = h * 31 + lineSpacing.toRawBits()
        h = h * 31 + colorArgb
        h = h * 31 + canvasWidth
        h = h * 31 + item.style.hashCode()
        return h
    }

    // endregion

    /** Releases the cached bitmap (editor exit). */
    fun release() {
        cachedBitmap?.takeIf { !it.isRecycled }?.recycle()
        cachedBitmap = null
        cacheKey = 0L
    }
}
