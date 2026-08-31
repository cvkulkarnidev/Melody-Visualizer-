package dev.cvkulkarnidev.melodyvisualizer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import dev.cvkulkarnidev.melodyvisualizer.music.DetectedNoteEvent
import dev.cvkulkarnidev.melodyvisualizer.music.MusicNote
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

enum class InstrumentSound(val label: String) {
    Piano("Piano"),
    Harmonium("Harmonium"),
}

/** A small offline synthesizer; no audio sample download or network access is needed. */
class PianoSynth {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "melody-instrument-synth")
    }
    private val sampleCache = LinkedHashMap<SampleKey, ShortArray>()
    private var activeTrack: AudioTrack? = null
    private var releaseTask: ScheduledFuture<*>? = null
    private val sequenceTasks = mutableListOf<ScheduledFuture<*>>()

    fun play(
        note: MusicNote,
        instrument: InstrumentSound,
        durationMillis: Long = PREVIEW_DURATION_MILLIS,
    ) {
        executor.execute {
            cancelSequence()
            playInternal(note, instrument, durationMillis)
        }
    }

    fun playSequence(
        notes: List<DetectedNoteEvent>,
        instrument: InstrumentSound,
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
                        playInternal(event.note, instrument, event.durationMillis)
                        onNote(index)
                    },
                    (event.startMillis - sequenceStart).coerceAtLeast(0L),
                    TimeUnit.MILLISECONDS,
                )
            }
            val releaseMillis = InstrumentWaveformGenerator.releaseMillis(instrument)
            val endDelay = notes.last().endMillis - sequenceStart + releaseMillis + 100L
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
        executor.execute {
            cancelSequence()
            releaseActiveTrack()
        }
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

    private fun playInternal(
        note: MusicNote,
        instrument: InstrumentSound,
        requestedDurationMillis: Long,
    ) {
        releaseActiveTrack()
        val durationMillis = requestedDurationMillis
            .coerceIn(MINIMUM_DURATION_MILLIS, MAXIMUM_DURATION_MILLIS)
            .roundToCacheBucket()
        val key = SampleKey(note.midi, instrument, durationMillis)
        val samples = sampleCache.getOrPut(key) {
            InstrumentWaveformGenerator.synthesize(
                frequencyHz = note.frequencyHz,
                durationMillis = durationMillis,
                instrument = instrument,
            )
        }
        while (sampleCache.size > MAX_CACHED_SAMPLES) {
            sampleCache.remove(sampleCache.keys.first())
        }

        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(InstrumentWaveformGenerator.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * Short.SIZE_BYTES)
                .build()
        }.getOrNull() ?: return

        activeTrack = track
        track.write(samples, 0, samples.size)
        track.setVolume(if (instrument == InstrumentSound.Harmonium) 0.52f else 0.58f)
        track.play()
        val playbackMillis = samples.size * 1_000L / InstrumentWaveformGenerator.SAMPLE_RATE
        releaseTask = executor.schedule(
            { releaseActiveTrack() },
            playbackMillis + 60L,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun Long.roundToCacheBucket(): Long = ((this + 25L) / 50L) * 50L

    private data class SampleKey(
        val midi: Int,
        val instrument: InstrumentSound,
        val durationMillis: Long,
    )

    private companion object {
        const val PREVIEW_DURATION_MILLIS = 900L
        const val MINIMUM_DURATION_MILLIS = 120L
        const val MAXIMUM_DURATION_MILLIS = 7_500L
        const val MAX_CACHED_SAMPLES = 18
    }
}

/** Pure waveform generator kept separate so its envelopes can be unit tested. */
internal object InstrumentWaveformGenerator {
    const val SAMPLE_RATE = 44_100

    fun releaseMillis(instrument: InstrumentSound): Long = when (instrument) {
        InstrumentSound.Piano -> 520L
        InstrumentSound.Harmonium -> 260L
    }

    fun synthesize(
        frequencyHz: Double,
        durationMillis: Long,
        instrument: InstrumentSound,
    ): ShortArray {
        val releaseMillis = releaseMillis(instrument)
        val totalMillis = durationMillis + releaseMillis
        val sampleCount = (SAMPLE_RATE * totalMillis / 1_000L).toInt().coerceAtLeast(1)
        return ShortArray(sampleCount) { index ->
            val time = index.toDouble() / SAMPLE_RATE
            val envelope = envelope(time, durationMillis / 1_000.0, releaseMillis / 1_000.0, instrument)
            val signal = when (instrument) {
                InstrumentSound.Piano -> pianoSignal(frequencyHz, time)
                InstrumentSound.Harmonium -> harmoniumSignal(frequencyHz, time)
            }
            val gain = if (instrument == InstrumentSound.Harmonium) 0.46 else 0.57
            (signal * envelope * gain * Short.MAX_VALUE)
                .coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble())
                .toInt()
                .toShort()
        }
    }

    private fun envelope(
        time: Double,
        heldSeconds: Double,
        releaseSeconds: Double,
        instrument: InstrumentSound,
    ): Double {
        val attackSeconds = if (instrument == InstrumentSound.Harmonium) 0.055 else 0.008
        val attack = (time / attackSeconds).coerceIn(0.0, 1.0)
        val heldEnvelope = when (instrument) {
            InstrumentSound.Piano -> 0.14 + 0.86 * exp(-time * 1.15)
            InstrumentSound.Harmonium -> 0.94 + 0.025 * sin(2.0 * PI * 4.7 * time)
        }
        if (time <= heldSeconds) return attack * heldEnvelope

        val releaseProgress = ((time - heldSeconds) / releaseSeconds).coerceIn(0.0, 1.0)
        val smoothRelease = 1.0 - releaseProgress * releaseProgress * (3.0 - 2.0 * releaseProgress)
        return attack * heldEnvelope * smoothRelease
    }

    private fun pianoSignal(frequencyHz: Double, time: Double): Double {
        var signal = 0.0
        for (harmonic in PIANO_HARMONICS.indices) {
            val multiplier = harmonic + 1
            if (frequencyHz * multiplier < SAMPLE_RATE / 2.0) {
                val inharmonicity = 1.0 + 0.00012 * multiplier * multiplier
                signal += PIANO_HARMONICS[harmonic] *
                    sin(2.0 * PI * frequencyHz * multiplier * inharmonicity * time)
            }
        }
        val hammer = 0.055 * exp(-time * 36.0) *
            sin(2.0 * PI * frequencyHz * 7.03 * time)
        return signal + hammer
    }

    private fun harmoniumSignal(frequencyHz: Double, time: Double): Double {
        val vibrato = 1.0 + 0.0012 * sin(2.0 * PI * 5.1 * time)
        var signal = 0.0
        for (harmonic in HARMONIUM_HARMONICS.indices) {
            val multiplier = harmonic + 1
            if (frequencyHz * multiplier < SAMPLE_RATE / 2.0) {
                signal += HARMONIUM_HARMONICS[harmonic] *
                    sin(2.0 * PI * frequencyHz * multiplier * vibrato * time)
            }
        }
        return signal
    }

    private val PIANO_HARMONICS = doubleArrayOf(0.76, 0.23, 0.12, 0.065, 0.032)
    private val HARMONIUM_HARMONICS = doubleArrayOf(0.62, 0.30, 0.18, 0.10, 0.06)
}
