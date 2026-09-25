package com.vitacut.core.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import com.vitacut.core.common.logging.VitaLog
import com.vitacut.core.rendering.effects.CubeLut
import com.vitacut.core.rendering.overlay.OverlayComposer
import com.vitacut.core.rendering.pipeline.EffectsPipelineBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies the two optional render integrations the export composition needs:
 * decoded bitmaps for image overlays and parsed `.cube` LUTs.
 *
 * Abstracted as an interface so tests (and hypothetical alternative image loaders) can inject
 * fakes without pulling Android decoding into the engine.
 */
interface ExportResourceProvider {
    fun imageFrameSource(): OverlayComposer.ImageFrameSource
    fun lutResolver(): EffectsPipelineBuilder.LutResolver
}

/**
 * Default implementation using [BitmapFactory] and [CubeLut] directly — no third-party image
 * loader required at export time. Both caches are bounded [LruCache]s so long exports on
 * low-memory devices degrade by re-decoding, never by OOM.
 */
@Singleton
class DefaultExportResourceProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : ExportResourceProvider {

    private val bitmapCache = LruCache<String, Bitmap>(maxOf(8, maxBitmapBytes()))

    override fun imageFrameSource(): OverlayComposer.ImageFrameSource =
        OverlayComposer.ImageFrameSource { uriString, targetWidth ->
            val key = "$uriString@${targetWidth}"
            bitmapCache.get(key) ?: decodeScaled(uriString, targetWidth)?.also { bitmapCache.put(key, it) }
        }

    override fun lutResolver(): EffectsPipelineBuilder.LutResolver =
        EffectsPipelineBuilder.LutResolver { assetName ->
            lutCache.get(assetName) ?: parseLut(assetName)?.also { lutCache.put(assetName, it) }
        }

    private val lutCache = LruCache<String, EffectsPipelineBuilder.LutTexture>(4)

    private fun maxBitmapBytes(): Int {
        val maxMemory = Runtime.getRuntime().maxMemory() / 1024L
        return (maxMemory / 8).toInt().coerceIn(1024, 16 * 1024) // KB
    }

    private fun decodeScaled(uriString: String, targetWidth: Int): Bitmap? = runCatching {
        val uri = Uri.parse(uriString)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrElse {
        VitaLog.w("ExportResources", "Failed to decode overlay image $uriString: ${it.message}")
        null
    }

    /** Looks for `name` / `name.cube` in the app LUT folder first, then bundled assets. */
    private fun parseLut(assetName: String): EffectsPipelineBuilder.LutTexture? = runCatching {
        val lut = openLutStream(assetName)?.use { CubeLut.parse(it) } ?: return@runCatching null
        val bitmap = CubeLut.bakeToBitmap(lut)
        EffectsPipelineBuilder.LutTexture(bitmapProvider = { bitmap }, size = lut.size)
    }.getOrElse {
        VitaLog.w("ExportResources", "Failed to load LUT $assetName: ${it.message}")
        null
    }

    private fun openLutStream(assetName: String): java.io.InputStream? {
        val candidates = listOf(
            File(context.filesDir, "luts/$assetName"),
            File(context.filesDir, "luts/$assetName.cube"),
            File(context.cacheDir, "luts/$assetName"),
        )
        for (file in candidates) {
            if (file.isFile) return runCatching { file.inputStream() }.getOrNull()
        }
        return runCatching { context.assets.open("luts/$assetName") }.getOrNull()
            ?: runCatching { context.assets.open("luts/$assetName.cube") }.getOrNull()
    }
}
