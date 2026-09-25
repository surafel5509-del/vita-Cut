package com.vitacut.core.ai

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.vitacut.core.ai.reframe.AutoReframeAnalyzer
import com.vitacut.core.common.logging.VitaLog
import com.vitacut.core.common.result.VitaError
import com.vitacut.core.common.result.VitaResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/** One detected object, normalized to frame fractions so callers never juggle pixel sizes. */
data class Detection(
    val centerX: Float,
    val centerY: Float,
    val widthFraction: Float,
    val heightFraction: Float,
    val label: String?,
    val confidence: Float,
)

/**
 * ML Kit object detection wrapper (bundled model → works offline). Powers:
 * - auto-reframe subject observations ([detectionsOverTime] via sampled video frames),
 * - "detect objects" assist for masking/stickers,
 * - an initial-region guess for motion tracking.
 *
 * Every call returns [VitaResult]; a failure degrades to the manual flow, never a crash.
 */
@Singleton
class ObjectDetector @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val detector: ObjectDetector by lazy {
        ObjectDetection.getClient(
            ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .build(),
        )
    }

    suspend fun detect(bitmap: Bitmap): VitaResult<List<Detection>> = runCatching {
        val image = InputImage.fromBitmap(bitmap, 0)
        val results = detector.process(image).await()
        VitaResult.Success(results.map { it.toDetection(bitmap.width, bitmap.height) })
    }.getOrElse {
        VitaLog.w("ObjectDetector", "Detection failed: ${it.message}")
        VitaResult.Failure(VitaError.CapabilityUnavailable("ai_detection_failed"))
    }

    suspend fun detect(uri: Uri): VitaResult<List<Detection>> = runCatching {
        val image = InputImage.fromFilePath(context, uri)
        val results = detector.process(image).await()
        VitaResult.Success(
            results.map { it.toDetection(max(1, image.width), max(1, image.height)) },
        )
    }.getOrElse {
        VitaLog.w("ObjectDetector", "Detection from URI failed: ${it.message}")
        VitaResult.Failure(VitaError.CorruptMedia(it.message ?: "detection"))
    }

    /** Picks the most prominent detection (largest area × confidence) as the "main subject". */
    fun primarySubject(detections: List<Detection>): Detection? =
        detections.maxByOrNull { it.widthFraction * it.heightFraction * it.confidence }

    /** Converts one detection into a reframe observation. */
    fun toSubjectFrame(detection: Detection, timeUs: Long): AutoReframeAnalyzer.SubjectFrame =
        AutoReframeAnalyzer.SubjectFrame(
            timeUs = timeUs,
            centerX = detection.centerX,
            centerY = detection.centerY,
            widthFraction = detection.widthFraction,
            heightFraction = detection.heightFraction,
            confidence = detection.confidence,
        )

    private fun DetectedObject.toDetection(frameWidth: Int, frameHeight: Int): Detection {
        val box = boundingBox
        return Detection(
            centerX = (box.exactCenterX() / frameWidth).coerceIn(0f, 1f),
            centerY = (box.exactCenterY() / frameHeight).coerceIn(0f, 1f),
            widthFraction = (box.width().toFloat() / frameWidth).coerceIn(0f, 1f),
            heightFraction = (box.height().toFloat() / frameHeight).coerceIn(0f, 1f),
            label = labels.maxByOrNull { it.confidence }?.text,
            confidence = labels.maxOfOrNull { it.confidence } ?: 0.5f,
        )
    }
}
