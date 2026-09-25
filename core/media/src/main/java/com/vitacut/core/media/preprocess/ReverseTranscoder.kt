package com.vitacut.core.media.preprocess

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import com.vitacut.core.common.logging.VitaLog
import com.vitacut.core.common.result.VitaError
import com.vitacut.core.common.result.VitaResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Pre-renders a reversed copy of a video segment.
 *
 * Media3/ExoPlayer have no reverse-playback support, so "Reverse" is implemented the honest way:
 * a background pre-process extracts frames back-to-front and re-encodes them with MediaCodec +
 * MediaMuxer into an H.264 proxy that preview *and* export both consume as the clip's source.
 *
 * Trade-offs (documented in the UI): the reversed proxy drops the original audio track and caps
 * at 1080p / 30 fps — identical to what mainstream mobile editors do for reversed segments.
 */
@Singleton
class ReverseTranscoder @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun reverseSegment(
        sourceUri: Uri,
        sourceInUs: Long,
        sourceOutUs: Long,
        outputFile: File,
        onProgress: (Float) -> Unit = {},
    ): VitaResult<File> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            retriever.setDataSource(context, sourceUri, emptyMap())

            var width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 1280
            var height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 720
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) {
                val tmp = width; width = height; height = tmp
            }
            // Cap the proxy at 1080p and keep dimensions even (encoder requirement).
            val scale = min(1f, 1920f / max(width, height))
            width = ((width * scale).toInt() / 2) * 2
            height = ((height * scale).toInt() / 2) * 2
            if (width <= 0 || height <= 0) {
                return@withContext VitaResult.Failure(VitaError.UnsupportedMedia("no video track"))
            }

            val frameRate = 30
            val stepUs = 1_000_000L / frameRate
            val durationUs = max(0L, sourceOutUs - sourceInUs)
            val frameCount = max(1L, durationUs / stepUs).toInt().coerceAtMost(MAX_FRAMES)

            outputFile.parentFile?.mkdirs()

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, estimateBitrate(width, height, frameRate))
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            var trackIndex = -1
            var muxerStarted = false
            val bufferInfo = MediaCodec.BufferInfo()
            val frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(frameBitmap)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)

            var decodedFrames = 0
            var outputDone = false

            while (!outputDone) {
                currentCoroutineContext().ensureActive()

                // Feed: extract frames from the END of the segment backwards.
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0 && decodedFrames < frameCount) {
                    val sourceTimeUs = sourceOutUs - (decodedFrames + 1) * stepUs
                    val frame: Bitmap? = runCatching {
                        retriever.getFrameAtTime(max(sourceInUs, sourceTimeUs), MediaMetadataRetriever.OPTION_CLOSEST)
                    }.getOrNull()
                    val inputBuffer = codec.getInputBuffer(inputIndex)!!
                    inputBuffer.clear()
                    val size = if (frame != null) {
                        canvas.drawColor(Color.BLACK)
                        canvas.save()
                        if (rotation != 0) {
                            canvas.rotate(-rotation.toFloat(), width / 2f, height / 2f)
                        }
                        val drawScale = min(width.toFloat() / frame.width, height.toFloat() / frame.height)
                        val dw = frame.width * drawScale
                        val dh = frame.height * drawScale
                        canvas.drawBitmap(
                            frame,
                            null,
                            android.graphics.RectF((width - dw) / 2f, (height - dh) / 2f, (width + dw) / 2f, (height + dh) / 2f),
                            paint,
                        )
                        canvas.restore()
                        frame.recycle()
                        val yuv = convertToI420(frameBitmap, width, height)
                        inputBuffer.put(yuv)
                        yuv.size
                    } else {
                        // Undecodable frame → push a black frame so timing stays intact.
                        val yuv = ByteArray(width * height * 3 / 2)
                        // Y=0 is black only with U/V at 128; fill chroma planes.
                        java.util.Arrays.fill(yuv, width * height, yuv.size, 128.toByte())
                        inputBuffer.put(yuv)
                        yuv.size
                    }
                    val presentationUs = decodedFrames.toLong() * stepUs
                    codec.queueInputBuffer(inputIndex, 0, size, presentationUs, 0)
                    decodedFrames++
                    onProgress(decodedFrames.toFloat() / (frameCount * 2))
                } else if (inputIndex >= 0 && decodedFrames >= frameCount) {
                    codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }

                // Drain encoder → muxer.
                var outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                while (outputIndex >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        codec.releaseOutputBuffer(outputIndex, false)
                        outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                        continue
                    }
                    if (trackIndex == -1) {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    if (bufferInfo.size > 0 && muxerStarted) {
                        val encoded = codec.getOutputBuffer(outputIndex)!!
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encoded, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                        break
                    }
                    outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                }
                onProgress(0.5f + 0.5f * decodedFrames / frameCount)
            }

            frameBitmap.recycle()
            codec.stop()
            codec.release()
            codec = null
            if (muxerStarted) muxer.stop()
            muxer.release()
            muxer = null

            if (outputFile.exists() && outputFile.length() > 0) {
                VitaResult.Success(outputFile)
            } else {
                VitaResult.Failure(VitaError.ExportFailed("reverse produced no output"))
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            outputFile.delete()
            throw ce
        } catch (t: Throwable) {
            VitaLog.e("ReverseTranscoder", "Reverse failed", t)
            outputFile.delete()
            VitaResult.Failure(VitaError.Unknown(t))
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { if (muxer != null) muxer.release() }
            runCatching { retriever.release() }
        }
    }

    private fun estimateBitrate(width: Int, height: Int, frameRate: Int): Int =
        (width * height * frameRate * 0.09).toInt().coerceIn(1_000_000, 16_000_000)

    /**
     * Converts an ARGB bitmap to a planar I420 buffer (Y + U + V planes). Straightforward
     * integer math; runs once per frame on the media dispatcher so allocation stays bounded.
     */
    private fun convertToI420(bitmap: Bitmap, width: Int, height: Int): ByteArray {
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val yuv = ByteArray(width * height * 3 / 2)
        val uOffset = width * height
        val vOffset = uOffset + width * height / 4

        var yIndex = 0
        var uvIndex = 0
        for (row in 0 until height) {
            for (col in 0 until width) {
                val pixel = pixels[row * width + col]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                val y = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)
                yuv[yIndex++] = y.toByte()

                if (row % 2 == 0 && col % 2 == 0) {
                    val u = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    val v = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    yuv[uOffset + uvIndex] = u.toByte()
                    yuv[vOffset + uvIndex] = v.toByte()
                    uvIndex++
                }
            }
        }
        return yuv
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
        private const val MAX_FRAMES = 30 * 60 * 5 // hard cap: 5 minutes of 30fps
    }
}
