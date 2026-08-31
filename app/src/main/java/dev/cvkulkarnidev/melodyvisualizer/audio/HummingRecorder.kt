package dev.cvkulkarnidev.melodyvisualizer.audio

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import java.io.File

data class CompletedRecording(
    val uri: Uri,
    val displayName: String,
)

/** Records a temporary AAC/M4A file. Analysis begins only after [stop] is called. */
class HummingRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    @Suppress("DEPRECATION")
    fun start() {
        check(recorder == null) { "A recording is already in progress." }
        val file = File.createTempFile("humming_", ".m4a", context.cacheDir)
        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }

        try {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setAudioSamplingRate(44_100)
            mediaRecorder.setAudioEncodingBitRate(128_000)
            mediaRecorder.setOutputFile(file.absolutePath)
            mediaRecorder.prepare()
            mediaRecorder.start()
            outputFile = file
            recorder = mediaRecorder
        } catch (error: Exception) {
            mediaRecorder.release()
            file.delete()
            throw error
        }
    }

    fun currentLevel(): Float {
        val amplitude = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
        return (amplitude / 16_000f).coerceIn(0f, 1f)
    }

    fun stop(): CompletedRecording {
        val activeRecorder = recorder ?: error("No recording is in progress.")
        val file = outputFile ?: error("The recording file is unavailable.")
        recorder = null
        outputFile = null
        try {
            activeRecorder.stop()
        } catch (error: RuntimeException) {
            file.delete()
            throw IllegalStateException("The recording was too short. Please record for at least one second.", error)
        } finally {
            activeRecorder.release()
        }
        return CompletedRecording(
            uri = Uri.fromFile(file),
            displayName = "My humming.m4a",
        )
    }

    fun cancel() {
        val activeRecorder = recorder
        recorder = null
        runCatching { activeRecorder?.stop() }
        activeRecorder?.release()
        outputFile?.delete()
        outputFile = null
    }

    fun release() = cancel()
}
