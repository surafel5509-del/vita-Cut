package com.vitacut.core.media.screenrecord

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.vitacut.core.common.logging.VitaLog
import java.io.File

/**
 * Foreground service capturing the screen through MediaProjection.
 *
 * Flow (handled by [ScreenRecordController] in the UI layer):
 * 1. UI obtains user consent via MediaProjectionManager.createScreenCaptureIntent().
 * 2. UI starts this service with the consent result data.
 * 3. Service runs a MediaRecorder-fed VirtualDisplay until stopped.
 *
 * The output is a plain MP4 in app cache; the user is then offered "Open in Vita Cut" which
 * imports it through the regular media pipeline.
 */
class ScreenRecordService : Service() {

    companion object {
        const val ACTION_START = "com.vitacut.action.START_SCREEN_RECORD"
        const val ACTION_STOP = "com.vitacut.action.STOP_SCREEN_RECORD"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val CHANNEL_ID = "vita_screen_record"
        const val NOTIFICATION_ID = 4242

        /** Broadcast when recording finishes, carrying the output path. */
        const val ACTION_RECORDING_FINISHED = "com.vitacut.broadcast.RECORDING_FINISHED"
        const val EXTRA_OUTPUT_PATH = "output_path"
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (data == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundNotification()
                startRecording(resultCode, data)
            }

            ACTION_STOP -> stopRecording(success = true)
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen recording",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)

        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, ScreenRecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Vita Cut")
            .setContentText("Recording screen…")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startRecording(resultCode: Int, data: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        if (mediaProjection == null) {
            stopSelf()
            return
        }
        projection = mediaProjection

        val metrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        // Encoders require even dimensions.
        val width = (metrics.widthPixels / 2) * 2
        val height = (metrics.heightPixels / 2) * 2
        val density = metrics.densityDpi

        val output = File(cacheDir, "screenrecord/rec_${System.currentTimeMillis()}.mp4")
        output.parentFile?.mkdirs()
        outputFile = output

        val mediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setOutputFile(output.absolutePath)
            mediaRecorder.setVideoSize(width, height)
            mediaRecorder.setVideoFrameRate(30)
            mediaRecorder.setVideoEncodingBitRate(12_000_000)
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            mediaRecorder.prepare()
            mediaRecorder.start()
            recorder = mediaRecorder
        } catch (t: Throwable) {
            VitaLog.e("ScreenRecordService", "Recorder setup failed", t)
            runCatching { mediaRecorder.release() }
            stopRecording(success = false)
            return
        }

        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopRecording(success = true)
            }
        }, handler)

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "vita-cut-screen",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mediaRecorder.surface,
            null,
            handler,
        )
    }

    private fun stopRecording(success: Boolean) {
        runCatching { recorder?.stop() }
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { projection?.stop() }
        projection = null

        val output = outputFile
        outputFile = null
        if (success && output != null && output.exists() && output.length() > 0) {
            sendBroadcast(Intent(ACTION_RECORDING_FINISHED).putExtra(EXTRA_OUTPUT_PATH, output.absolutePath))
        } else {
            output?.delete()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopRecording(success = false)
        super.onDestroy()
    }
}
