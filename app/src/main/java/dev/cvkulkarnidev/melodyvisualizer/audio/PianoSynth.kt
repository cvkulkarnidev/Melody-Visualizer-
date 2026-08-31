package dev.cvkulkarnidev.melodyvisualizer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import dev.cvkulkarnidev.melodyvisualizer.music.MusicNote
import dev.cvkulkarnidev.melodyvisualizer.music.DetectedNoteEvent
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** A small offline piano-like synthesizer; no sample download or network access is needed. */
class PianoSynth {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "melody-piano-synth")
    }
    private val sampleCache = LinkedHashMap<Int, ShortArray>()
    private var activeTrack: AudioTrack? = null
    private var releaseTask: ScheduledFuture<*>? = null
    private val sequenceTasks = mutableListOf<ScheduledFuture<*>>()

    fun play(note: MusicNote) {
        executor.execute {
            cancelSequence()
            playInternal(note)
        }
    }

    fun playSequence(
        notes: List<DetectedNoteEvent>,
        onNote: (Int) -> Unit,
        onComplete: () -> Unit,
    ) {
        executor.execute {
            cancelSequence()
            releaseActiveTrack()
            if (notes.isEmpty()) {
                onComplete()
                return@execute
            }
            val sequenceStart = notes.first().startMillis
            notes.forEachIndexed { index, event ->
                sequenceTasks += executor.schedule(
                    {
                        playInternal(event.note)
                        onNote(index)
                    },
                    (event.startMillis - sequenceStart).coerceAtLeast(0L),
                    TimeUnit.MILLISECONDS,
                )
            }
            val endDelay = notes.last().endMillis - sequenceStart + 120L
            sequenceTasks += executor.schedule(
                {
                    releaseActiveTrack()
                    sequenceTasks.clear()
                    onComplete()
                },
                endDelay,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    fun stop() {
        executor.execute {
            cancelSequence()
            releaseActiveTrack()
        }
    }

    fun release() {
        stop()
        executor.shutdown()
    }

    private fun releaseActiveTrack() {
        releaseTask?.cancel(false)
        releaseTask = null
        activeTrack?.let { track ->
            runCatching { track.stop() }
            track.release()
        }
        activeTrack = null
    }

    private fun cancelSequence() {
        sequenceTasks.forEach { it.cancel(false) }
        sequenceTasks.clear()
    }

    private fun playInternal(note: MusicNote) {
        releaseActiveTrack()
        val samples = sampleCache.getOrPut(note.midi) { synthesize(note.frequencyHz) }
        if (sampleCache.size > MAX_CACHED_NOTES) {
            sampleCache.remove(sampleCache.keys.first())
        }

        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * Short.SIZE_BYTES)
                .build()
        }.getOrNull() ?: return

        activeTrack = track
        track.write(samples, 0, samples.size)
        track.setVolume(0.48f)
        track.play()
        releaseTask = executor.schedule(
            { releaseActiveTrack() },
            NOTE_DURATION_MILLIS + 80L,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun synthesize(frequencyHz: Double): ShortArray {
        val sampleCount = SAMPLE_RATE * NOTE_DURATION_MILLIS / 1_000
        return ShortArray(sampleCount) { index ->
            val time = index.toDouble() / SAMPLE_RATE
            val attack = 1.0 - exp(-time * 650.0)
            val decay = exp(-time * 3.5)
            val envelope = attack * decay

            var signal = 0.0
            for (harmonic in HARMONIC_WEIGHTS.indices) {
                val multiplier = harmonic + 1
                if (frequencyHz * multiplier < SAMPLE_RATE / 2.0) {
                    signal += HARMONIC_WEIGHTS[harmonic] *
                        sin(2.0 * PI * frequencyHz * multiplier * time)
                }
            }
            val hammer = 0.05 * exp(-time * 34.0) * sin(2.0 * PI * frequencyHz * 7.03 * time)
            ((signal * envelope + hammer) * Short.MAX_VALUE * 0.62)
                .coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble())
                .toInt()
                .toShort()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val NOTE_DURATION_MILLIS = 850
        const val MAX_CACHED_NOTES = 24
        val HARMONIC_WEIGHTS = doubleArrayOf(0.78, 0.24, 0.12, 0.07, 0.035)
    }
}
