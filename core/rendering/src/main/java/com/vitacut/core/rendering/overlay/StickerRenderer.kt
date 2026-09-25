package com.vitacut.core.rendering.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.util.LruCache
import com.vitacut.core.common.logging.VitaLog
import com.vitacut.core.model.KeyframeMath
import com.vitacut.core.model.KeyframeProperty
import com.vitacut.core.model.StickerItem
import com.vitacut.core.model.StickerSource
import com.vitacut.core.model.TrackPath
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Catalog of built-in stickers drawn procedurally with the Canvas API — no copyrighted artwork,
 * no binary assets: shapes, arrows, social-style glyphs and reactions rendered from vectors.
 * Keys are stable identifiers persisted inside projects.
 */
object BuiltInStickers {

    val CATEGORIES: Map<String, List<String>> = linkedMapOf(
        "shapes" to listOf("circle", "square", "triangle", "star", "heart", "sparkle"),
        "arrows" to listOf("arrow_right", "arrow_left", "arrow_up", "arrow_curve", "pointer"),
        "social" to listOf("like", "comment", "share_badge", "bell", "play_button"),
        "reactions" to listOf("emoji_smile", "emoji_heart_eyes", "emoji_fire", "emoji_clap", "emoji_100"),
        "decorative" to listOf("frame_corner", "burst", "underline_swoosh", "confetti", "crown"),
    )

    val ALL_KEYS: List<String> = CATEGORIES.values.flatten()

    fun draw(key: String, sizePx: Int, primaryColor: Int, accentColor: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = primaryColor }
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
        val stroke = Paint(paint).apply {
            style = Paint.Style.STROKE
            strokeWidth = sizePx * 0.06f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val s = sizePx.toFloat()
        val rect = RectF(s * 0.1f, s * 0.1f, s * 0.9f, s * 0.9f)

        when (key) {
            "circle" -> canvas.drawOval(rect, stroke)
            "square" -> canvas.drawRect(rect, stroke)
            "triangle" -> {
                val path = Path().apply {
                    moveTo(s * 0.5f, s * 0.12f)
                    lineTo(s * 0.88f, s * 0.85f)
                    lineTo(s * 0.12f, s * 0.85f)
                    close()
                }
                canvas.drawPath(path, stroke)
            }

            "star" -> canvas.drawPath(starPath(s, 5, 0.38f, 0.5f), paint)
            "heart" -> canvas.drawPath(heartPath(s), paint)
            "sparkle" -> canvas.drawPath(starPath(s, 4, 0.18f, 0.5f), paint)

            "arrow_right" -> drawArrow(canvas, stroke, s, 0f)
            "arrow_left" -> drawArrow(canvas, stroke, s, 180f)
            "arrow_up" -> drawArrow(canvas, stroke, s, 270f)
            "arrow_curve" -> {
                canvas.save()
                canvas.rotate(-30f, s / 2, s / 2)
                canvas.drawArc(rect, 200f, 250f, false, stroke)
                canvas.restore()
                drawArrow(canvas, stroke, s, 15f)
            }

            "pointer" -> {
                val path = Path().apply {
                    moveTo(s * 0.35f, s * 0.15f)
                    lineTo(s * 0.75f, s * 0.5f)
                    lineTo(s * 0.35f, s * 0.85f)
                    lineTo(s * 0.42f, s * 0.5f)
                    close()
                }
                canvas.drawPath(path, paint)
            }

            "like" -> {
                canvas.drawPath(thumbPath(s), paint)
                canvas.drawRoundRect(RectF(s * 0.2f, s * 0.55f, s * 0.45f, s * 0.8f), s * 0.04f, s * 0.04f, accent)
            }

            "comment" -> {
                canvas.drawRoundRect(rect, s * 0.15f, s * 0.15f, stroke)
                val tail = Path().apply {
                    moveTo(s * 0.3f, s * 0.85f)
                    lineTo(s * 0.35f, s * 0.98f)
                    lineTo(s * 0.5f, s * 0.88f)
                    close()
                }
                canvas.drawPath(tail, stroke)
            }

            "share_badge" -> {
                canvas.drawCircle(s * 0.5f, s * 0.5f, s * 0.38f, stroke)
                drawArrow(canvas, stroke, s * 0.6f, 0f)
            }

            "bell" -> {
                val path = Path().apply {
                    moveTo(s * 0.3f, s * 0.65f)
                    quadTo(s * 0.3f, s * 0.25f, s * 0.5f, s * 0.2f)
                    quadTo(s * 0.7f, s * 0.25f, s * 0.7f, s * 0.65f)
                    lineTo(s * 0.78f, s * 0.75f)
                    lineTo(s * 0.22f, s * 0.75f)
                    close()
                }
                canvas.drawPath(path, paint)
                canvas.drawCircle(s * 0.5f, s * 0.83f, s * 0.06f, accent)
            }

            "play_button" -> {
                canvas.drawCircle(s * 0.5f, s * 0.5f, s * 0.4f, stroke)
                val path = Path().apply {
                    moveTo(s * 0.4f, s * 0.32f)
                    lineTo(s * 0.68f, s * 0.5f)
                    lineTo(s * 0.4f, s * 0.68f)
                    close()
                }
                canvas.drawPath(path, paint)
            }

            "emoji_smile", "emoji_heart_eyes", "emoji_fire", "emoji_clap", "emoji_100" -> {
                val glyph = when (key) {
                    "emoji_smile" -> "🙂"
                    "emoji_heart_eyes" -> "😍"
                    "emoji_fire" -> "🔥"
                    "emoji_clap" -> "👏"
                    else -> "💯"
                }
                drawEmoji(canvas, glyph, s)
            }

            "frame_corner" -> {
                canvas.drawLine(s * 0.12f, s * 0.3f, s * 0.12f, s * 0.12f, stroke)
                canvas.drawLine(s * 0.12f, s * 0.12f, s * 0.3f, s * 0.12f, stroke)
                canvas.drawLine(s * 0.7f, s * 0.88f, s * 0.88f, s * 0.88f, stroke)
                canvas.drawLine(s * 0.88f, s * 0.88f, s * 0.88f, s * 0.7f, stroke)
            }

            "burst" -> canvas.drawPath(starPath(s, 12, 0.32f, 0.5f), accent)
            "underline_swoosh" -> {
                val path = Path().apply {
                    moveTo(s * 0.1f, s * 0.6f)
                    quadTo(s * 0.5f, s * 0.85f, s * 0.9f, s * 0.5f)
                }
                canvas.drawPath(path, stroke)
            }

            "confetti" -> {
                val rnd = java.util.Random(7)
                repeat(18) {
                    val x = s * (0.15f + rnd.nextFloat() * 0.7f)
                    val y = s * (0.15f + rnd.nextFloat() * 0.7f)
                    val confettiPaint = Paint(paint).apply {
                        color = Color.HSVToColor(floatArrayOf(rnd.nextFloat() * 360f, 0.8f, 0.95f))
                    }
                    canvas.save()
                    canvas.rotate(rnd.nextFloat() * 360f, x, y)
                    canvas.drawRect(x, y, x + s * 0.06f, y + s * 0.025f, confettiPaint)
                    canvas.restore()
                }
            }

            "crown" -> {
                val path = Path().apply {
                    moveTo(s * 0.15f, s * 0.75f)
                    lineTo(s * 0.2f, s * 0.35f)
                    lineTo(s * 0.375f, s * 0.55f)
                    lineTo(s * 0.5f, s * 0.25f)
                    lineTo(s * 0.625f, s * 0.55f)
                    lineTo(s * 0.8f, s * 0.35f)
                    lineTo(s * 0.85f, s * 0.75f)
                    close()
                }
                canvas.drawPath(path, paint)
            }

            else -> canvas.drawCircle(s / 2, s / 2, s * 0.35f, stroke)
        }
        return bitmap
    }

    private fun drawArrow(canvas: Canvas, stroke: Paint, s: Float, rotationDeg: Float) {
        canvas.save()
        canvas.rotate(rotationDeg, s / 2, s / 2)
        canvas.drawLine(s * 0.18f, s * 0.5f, s * 0.75f, s * 0.5f, stroke)
        canvas.drawLine(s * 0.75f, s * 0.5f, s * 0.58f, s * 0.33f, stroke)
        canvas.drawLine(s * 0.75f, s * 0.5f, s * 0.58f, s * 0.67f, stroke)
        canvas.restore()
    }

    private fun drawEmoji(canvas: Canvas, glyph: String, s: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = s * 0.7f
            textAlign = Paint.Align.CENTER
        }
        val fm = paint.fontMetrics
        canvas.drawText(glyph, s / 2f, s / 2f - (fm.ascent + fm.descent) / 2f, paint)
    }

    private fun starPath(s: Float, points: Int, innerRatio: Float, centerY: Float): Path {
        val path = Path()
        val outer = s * 0.4f
        val inner = outer * innerRatio
        val cx = s * 0.5f
        val cy = s * centerY
        for (i in 0 until points * 2) {
            val radius = if (i % 2 == 0) outer else inner
            val angle = Math.PI * i / points - Math.PI / 2
            val x = cx + (radius * cos(angle)).toFloat()
            val y = cy + (radius * sin(angle)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    private fun heartPath(s: Float): Path {
        val path = Path()
        val topCurveHeight = s * 0.3f
        path.moveTo(s * 0.5f, s * 0.85f)
        // Left side.
        path.cubicTo(s * 0.15f, s * 0.6f, s * 0.1f, topCurveHeight, s * 0.32f, topCurveHeight * 0.85f)
        path.cubicTo(s * 0.42f, topCurveHeight * 0.72f, s * 0.5f, s * 0.35f, s * 0.5f, s * 0.42f)
        // Right side (mirror).
        path.cubicTo(s * 0.5f, s * 0.35f, s * 0.58f, topCurveHeight * 0.72f, s * 0.68f, topCurveHeight * 0.85f)
        path.cubicTo(s * 0.9f, topCurveHeight, s * 0.85f, s * 0.6f, s * 0.5f, s * 0.85f)
        path.close()
        return path
    }

    private fun thumbPath(s: Float): Path {
        val path = Path()
        path.moveTo(s * 0.3f, s * 0.8f)
        path.lineTo(s * 0.3f, s * 0.45f)
        path.quadTo(s * 0.32f, s * 0.3f, s * 0.45f, s * 0.2f)
        path.quadTo(s * 0.55f, s * 0.14f, s * 0.58f, s * 0.28f)
        path.lineTo(s * 0.55f, s * 0.42f)
        path.lineTo(s * 0.75f, s * 0.42f)
        path.quadTo(s * 0.85f, s * 0.44f, s * 0.82f, s * 0.55f)
        path.lineTo(s * 0.72f, s * 0.78f)
        path.quadTo(s * 0.68f, s * 0.84f, s * 0.58f, s * 0.82f)
        path.close()
        return path
    }
}

/**
 * Rasterizes [StickerItem]s: emoji via the system font, built-ins via [BuiltInStickers],
 * imported stickers via sampled bitmap decoding. Placement includes keyframes and motion-tracking
 * bindings (the tracked path replaces translation when present).
 */
@Singleton
class StickerRenderer @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val importedCache = object : LruCache<String, Bitmap>(24) {}

    fun render(
        item: StickerItem,
        timelineTimeUs: Long,
        canvasWidth: Int,
        canvasHeight: Int,
        trackingPath: TrackPath?,
    ): RenderedOverlay? {
        val local = timelineTimeUs - item.timelineStartUs
        if (local < 0 || local >= item.durationUs) return null

        val sizePx = (item.sizeFraction * canvasHeight).toInt().coerceIn(16, 1024)
        val bitmap = bitmapFor(item.source, sizePx) ?: return null

        val opacity = keyframed(item.keyframes.trackFor(KeyframeProperty.OPACITY)?.keyframes ?: emptyList(), local, item.transform.opacity)

        var tx = item.transform.translationX
        var ty = item.transform.translationY
        var scale = 1f
        trackingPath?.sampleAt(timelineTimeUs)?.let { point ->
            tx = point.x
            ty = point.y
            scale = point.scale
        }

        val placement = OverlayPlacement.fromTransform(
            transform = item.transform,
            bitmapWidth = bitmap.width,
            bitmapHeight = bitmap.height,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            opacityOverride = opacity,
            scaleOverride = scale,
            translationOverrideX = tx,
            translationOverrideY = ty,
        )
        return RenderedOverlay(bitmap, placement)
    }

    private fun bitmapFor(source: StickerSource, sizePx: Int): Bitmap? = when (source) {
        is StickerSource.Emoji -> emojiBitmap(source.emoji, sizePx)

        is StickerSource.BuiltIn -> BuiltInStickers.draw(
            source.stickerKey,
            sizePx,
            primaryColor = Color.WHITE,
            accentColor = 0xFFFFD54F.toInt(),
        )

        is StickerSource.Imported -> importedBitmap(source.uri, sizePx)
    }

    private val emojiCache = object : LruCache<String, Bitmap>(32) {}

    private fun emojiBitmap(emoji: String, sizePx: Int): Bitmap? {
        val key = "$emoji|$sizePx"
        emojiCache.get(key)?.let { return it }
        return runCatching {
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sizePx * 0.8f }
            val fm = paint.fontMetrics
            canvas.drawText(
                emoji,
                sizePx / 2f - paint.measureText(emoji) / 2f,
                sizePx / 2f - (fm.ascent + fm.descent) / 2f,
                paint,
            )
            emojiCache.put(key, bitmap)
            bitmap
        }.getOrNull()
    }

    private fun importedBitmap(uri: String, sizePx: Int): Bitmap? {
        val key = "$uri|$sizePx"
        importedCache.get(key)?.let { return it }
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sizePx) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null
            val scaled = Bitmap.createScaledBitmap(decoded, sizePx, sizePx, true)
            if (scaled != decoded) decoded.recycle()
            importedCache.put(key, scaled)
            scaled
        }.getOrElse {
            VitaLog.w("StickerRenderer", "Cannot decode imported sticker $uri: ${it.message}")
            null
        }
    }

    private fun keyframed(keys: List<com.vitacut.core.model.Keyframe>, localUs: Long, base: Float): Float =
        if (keys.isEmpty()) base else KeyframeMath.evaluate(keys, localUs)

    fun clearCaches() {
        importedCache.evictAll()
        emojiCache.evictAll()
    }
}
