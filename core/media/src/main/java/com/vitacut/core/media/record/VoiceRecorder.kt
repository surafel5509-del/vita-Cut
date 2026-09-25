package com.vitacut.core.media.record

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.vitacut.core.common.logging.VitaLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** State machine of an in-editor voiceover recording session. */
enum class RecorderState { IDLE, RECORDING, PAUSED }

/**
 * Voiceover recorder (AAC in M4A container).
 *
 * Recording happens *while the timeline plays* — the editor keeps the transport running and the
 * recorder just writes to a file; on stop, the file is imported as an AUDIO asset and appended as
 * an [com.vitacut.core.model.AudioClipItem] at the transport position where recording started.
 * Pause/resume is supported on API 24+ (minSdk is 26, so always).
 */
@Singleton
class VoiceRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val _state = MutableStateFlow(RecorderState.IDLE)
    val state: StateFlow<RecorderState> = _state

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtElapsedMs = 0L
    private var accumulatedBeforePauseMs = 0L

    /** Absolute path of the in-progress/last recording. */
    val currentFilePath: String? get() = outputFile?.absolutePath

    /** Duration recorded so far in ms (live, for the recording HUD). */
    fun recordedDurationMs(): Long = when (_state.value) {
        RecorderState.RECORDING -> accumulatedBeforePauseMs + (elapsedRealtime() - startedAtElapsedMs)
        RecorderState.PAUSED -> accumulatedBeforePauseMs
        RecorderState.IDLE -> 0L
    }

    /**
     * Starts recording into a fresh temp file. Returns false (and stays IDLE) when the microphone
     * is unavailable — caller must hold RECORD_AUDIO permission before calling.
     */
    fun start(): Boolean {
        if (_state.value != RecorderState.IDLE) return false
        val file = File(context.cacheDir, "voiceover/vo_${System.currentTimeMillis()}.m4a")
        file.parentFile?.mkdirs()
        val mediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setAudioEncodingBitRate(192_000)
            mediaRecorder.setAudioSamplingRate(48_000)
            mediaRecorder.setOutputFile(file.absolutePath)
            mediaRecorder.prepare()
            mediaRecorder.start()
            recorder = mediaRecorder
            outputFile = file
            accumulatedBeforePauseMs = 0L
            startedAtElapsedMs = elapsedRealtime()
            _state.value = RecorderState.RECORDING
            true
        } catch (t: Throwable) {
            VitaLog.w("VoiceRecorder", "Failed to start recording: ${t.message}")
            runCatching { mediaRecorder.release() }
            _state.value = RecorderState.IDLE
            false
        }
    }

    fun pause(): Boolean {
        if (_state.value != RecorderState.RECORDING) return false
        return try {
            recorder?.pause()
            accumulatedBeforePauseMs += elapsedRealtime() - startedAtElapsedMs
            _state.value = RecorderState.PAUSED
            true
        } catch (t: Throwable) {
            VitaLog.w("VoiceRecorder", "Pause failed: ${t.message}")
            false
        }
    }

    fun resume(): Boolean {
        if (_state.value != RecorderState.PAUSED) return false
        return try {
            recorder?.resume()
            startedAtElapsedMs = elapsedRealtime()
            _state.value = RecorderState.RECORDING
            true
        } catch (t: Throwable) {
            VitaLog.w("VoiceRecorder", "Resume failed: ${t.message}")
            false
        }
    }

    /** Stops and returns the recorded file (null when nothing usable was recorded). */
    fun stop(): File? {
        val file = outputFile
        return try {
            recorder?.stop()
            file?.takeIf { it.exists() && it.length() > 1024 }
        } catch (t: Throwable) {
            // stop() throws when no valid sample was recorded — treat as "retake".
            VitaLog.w("VoiceRecorder", "Stop failed (empty recording?): ${t.message}")
            file?.delete()
            null
        } finally {
            runCatching { recorder?.release() }
            recorder = null
            outputFile = null
            _state.value = RecorderState.IDLE
        }
    }

    /** Discards the current take without producing a file. */
    fun cancel() {
        val file = outputFile
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        outputFile = null
        file?.delete()
        _state.value = RecorderState.IDLE
    }

    private fun elapsedRealtime(): Long = android.os.SystemClock.elapsedRealtime()
}
