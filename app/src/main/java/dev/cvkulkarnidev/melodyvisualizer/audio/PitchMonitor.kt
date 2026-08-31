package dev.cvkulkarnidev.melodyvisualizer.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class PitchMonitor(private val context: Context) {
    private val detector = YinPitchDetector(sampleRate = SAMPLE_RATE)
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "melody-pitch-monitor")
    }

    @Volatile
    private var audioRecord: AudioRecord? = null

    @SuppressLint("MissingPermission")
    fun start(
        onAnalysis: (FrameAnalysis) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!running.compareAndSet(false, true)) return
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            running.set(false)
            onError("Microphone permission is required.")
            return
        }

        executor.execute {
            val minimumBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minimumBuffer <= 0) {
                running.set(false)
                onError("This device could not open a microphone stream.")
                return@execute
            }

            val recorder = try {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(max(minimumBuffer * 2, FRAME_SIZE * 4))
                    .build()
            } catch (error: Exception) {
                running.set(false)
                onError("Could not start the microphone: ${error.message ?: "unknown error"}")
                return@execute
            }

            audioRecord = recorder
            val frame = ShortArray(FRAME_SIZE)

            try {
                recorder.startRecording()
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    error("Microphone did not enter recording state")
                }

                while (running.get()) {
                    var filled = 0
                    while (filled < frame.size && running.get()) {
                        val count = recorder.read(
                            frame,
                            filled,
                            frame.size - filled,
                            AudioRecord.READ_BLOCKING,
                        )
                        if (count < 0) error("Audio read failed with code $count")
                        if (count == 0) continue
                        filled += count
                    }
                    if (filled == frame.size && running.get()) {
                        onAnalysis(detector.analyse(frame))
                    }
                }
            } catch (error: Exception) {
                if (running.getAndSet(false)) {
                    onError("Listening stopped: ${error.message ?: "audio error"}")
                }
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
                audioRecord = null
            }
        }
    }

    fun stop() {
        running.set(false)
        runCatching { audioRecord?.stop() }
    }

    fun release() {
        stop()
        executor.shutdownNow()
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val FRAME_SIZE = 2_048
    }
}
