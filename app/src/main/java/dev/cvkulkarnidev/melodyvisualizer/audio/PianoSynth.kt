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
import kotlin.math.abs
import kotlin.math.cos
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
    private var activeTrackVolume = 0f
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
            releaseActiveTrack(fadeOut = true)
            if (notes.isEmpty()) {
                onComplete()
                return@execute
            }
            val samples = InstrumentWaveformGenerator.synthesizeSequence(notes, instrument)
            val track = createTrack(samples, instrument) ?: run {
                onComplete()
                return@execute
            }
            activeTrack = track
            activeTrackVolume = instrumentVolume(instrument)
            track.play()

            val sequenceStart = notes.first().startMillis
            notes.forEachIndexed { index, event ->
                sequenceTasks += executor.schedule(
                    {
                        onNote(index)
                    },
                    InstrumentWaveformGenerator.SEQUENCE_LEAD_IN_MILLIS +
                        (event.startMillis - sequenceStart).coerceAtLeast(0L),
                    TimeUnit.MILLISECONDS,
                )
            }
            val playbackMillis = samples.size * 1_000L / InstrumentWaveformGenerator.SAMPLE_RATE
            releaseTask = executor.schedule(
                {
                    releaseActiveTrack()
                    sequenceTasks.clear()
                    onComplete()
                },
                playbackMillis + 60L,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    fun stop() {
        executor.execute {
            cancelSequence()
            releaseActiveTrack(fadeOut = true)
        }
    }

    fun release() {
        executor.execute {
            cancelSequence()
            releaseActiveTrack()
        }
        executor.shutdown()
    }

    private fun releaseActiveTrack(fadeOut: Boolean = false) {
        releaseTask?.cancel(false)
        releaseTask = null
        activeTrack?.let { track ->
            if (fadeOut && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                repeat(FADE_OUT_STEPS) { step ->
                    val remaining = (FADE_OUT_STEPS - step - 1).toFloat() / FADE_OUT_STEPS
                    runCatching { track.setVolume(activeTrackVolume * remaining) }
                    runCatching { Thread.sleep(FADE_OUT_STEP_MILLIS) }
                }
            }
            runCatching { track.stop() }
            track.release()
        }
        activeTrack = null
        activeTrackVolume = 0f
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
        releaseActiveTrack(fadeOut = true)
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

        val playbackSamples = InstrumentWaveformGenerator.prependSilence(samples)
        val track = createTrack(playbackSamples, instrument) ?: return

        activeTrack = track
        activeTrackVolume = instrumentVolume(instrument)
        track.play()
        val playbackMillis = playbackSamples.size * 1_000L / InstrumentWaveformGenerator.SAMPLE_RATE
        releaseTask = executor.schedule(
            { releaseActiveTrack() },
            playbackMillis + 60L,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun createTrack(samples: ShortArray, instrument: InstrumentSound): AudioTrack? {
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
        }.getOrNull() ?: return null

        // MODE_STATIC tracks start in STATE_NO_STATIC_DATA and become initialized only after
        // their first successful write. Rejecting that state here makes playback end instantly.
        val written = track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        if (written != samples.size || track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            return null
        }
        track.setVolume(instrumentVolume(instrument))
        return track
    }

    private fun instrumentVolume(instrument: InstrumentSound): Float =
        if (instrument == InstrumentSound.Harmonium) 0.52f else 0.58f

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
        const val FADE_OUT_STEPS = 6
        const val FADE_OUT_STEP_MILLIS = 2L
    }
}

/** Pure waveform generator kept separate so its envelopes can be unit tested. */
internal object InstrumentWaveformGenerator {
    const val SAMPLE_RATE = 44_100
    const val SEQUENCE_LEAD_IN_MILLIS = 24L

    fun releaseMillis(instrument: InstrumentSound): Long = when (instrument) {
        InstrumentSound.Piano -> 520L
        InstrumentSound.Harmonium -> 260L
    }

    fun synthesize(
        frequencyHz: Double,
        durationMillis: Long,
        instrument: InstrumentSound,
    ): ShortArray = synthesizeWithRelease(
        frequencyHz = frequencyHz,
        durationMillis = durationMillis,
        releaseMillis = releaseMillis(instrument),
        instrument = instrument,
    )

    private fun synthesizeWithRelease(
        frequencyHz: Double,
        durationMillis: Long,
        releaseMillis: Long,
        instrument: InstrumentSound,
    ): ShortArray {
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

    /**
     * Renders a complete melody into one PCM buffer. Release tails can overlap the next attack,
     * avoiding the click and device-start noise caused by constructing an AudioTrack per note.
     */
    fun synthesizeSequence(
        notes: List<DetectedNoteEvent>,
        instrument: InstrumentSound,
    ): ShortArray {
        if (notes.isEmpty()) return ShortArray(0)
        val ordered = notes.sortedBy { it.startMillis }
        val sequenceStart = ordered.first().startMillis
        val finalSoundEnd = ordered.indices.maxOf { index ->
            ordered[index].startMillis - sequenceStart +
                effectiveHeldDurationMillis(ordered, index) +
                effectiveReleaseMillis(ordered, index, instrument)
        }
        val totalMillis = SEQUENCE_LEAD_IN_MILLIS + finalSoundEnd
        val mix = FloatArray((SAMPLE_RATE * totalMillis / 1_000L).toInt().coerceAtLeast(1))

        ordered.forEachIndexed { index, event ->
            val heldMillis = effectiveHeldDurationMillis(ordered, index)
            val waveform = synthesizeWithRelease(
                frequencyHz = event.note.frequencyHz,
                durationMillis = heldMillis,
                releaseMillis = effectiveReleaseMillis(ordered, index, instrument),
                instrument = instrument,
            )
            val startSample = (
                (SEQUENCE_LEAD_IN_MILLIS + event.startMillis - sequenceStart) * SAMPLE_RATE / 1_000L
                ).toInt()
            val available = minOf(waveform.size, mix.size - startSample)
            for (sample in 0 until available) {
                mix[startSample + sample] += waveform[sample] / Short.MAX_VALUE.toFloat()
            }
        }

        val peak = mix.maxOf { abs(it) }.coerceAtLeast(1f)
        val scale = minOf(1f, 0.94f / peak)
        return ShortArray(mix.size) { index ->
            (mix[index] * scale * Short.MAX_VALUE)
                .coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
                .toInt()
                .toShort()
        }
    }

    fun prependSilence(samples: ShortArray): ShortArray {
        val silenceSamples = (SAMPLE_RATE * SEQUENCE_LEAD_IN_MILLIS / 1_000L).toInt()
        return ShortArray(silenceSamples + samples.size).also { output ->
            samples.copyInto(output, destinationOffset = silenceSamples)
        }
    }

    internal fun effectiveHeldDurationMillis(notes: List<DetectedNoteEvent>, index: Int): Long {
        val event = notes[index]
        val next = notes.getOrNull(index + 1) ?: return event.durationMillis
        val gap = next.startMillis - event.endMillis
        return if (gap in 1..MAX_LEGATO_GAP_MILLIS) {
            event.durationMillis + gap
        } else {
            event.durationMillis
        }
    }

    internal fun effectiveReleaseMillis(
        notes: List<DetectedNoteEvent>,
        index: Int,
        instrument: InstrumentSound,
    ): Long {
        val event = notes[index]
        val next = notes.getOrNull(index + 1) ?: return releaseMillis(instrument)
        val gap = next.startMillis - event.endMillis
        if (gap <= MAX_LEGATO_GAP_MILLIS) return releaseMillis(instrument)
        return minOf(releaseMillis(instrument), (gap / 3L).coerceAtLeast(MIN_REST_RELEASE_MILLIS))
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
        // Integrate the desired instantaneous-frequency modulation. Multiplying frequency by
        // vibrato inside `frequency * time` makes the pitch excursion grow with note duration.
        val phaseTime = time + HARMONIUM_VIBRATO_DEPTH *
            (1.0 - cos(2.0 * PI * HARMONIUM_VIBRATO_RATE_HZ * time)) /
            (2.0 * PI * HARMONIUM_VIBRATO_RATE_HZ)
        var signal = 0.0
        for (harmonic in HARMONIUM_HARMONICS.indices) {
            val multiplier = harmonic + 1
            if (frequencyHz * multiplier < SAMPLE_RATE / 2.0) {
                signal += HARMONIUM_HARMONICS[harmonic] *
                    sin(2.0 * PI * frequencyHz * multiplier * phaseTime)
            }
        }
        return signal
    }

    private val PIANO_HARMONICS = doubleArrayOf(0.76, 0.23, 0.12, 0.065, 0.032)
    private val HARMONIUM_HARMONICS = doubleArrayOf(0.62, 0.30, 0.18, 0.10, 0.06)
    private const val MAX_LEGATO_GAP_MILLIS = 160L
    private const val MIN_REST_RELEASE_MILLIS = 48L
    private const val HARMONIUM_VIBRATO_DEPTH = 0.0012
    private const val HARMONIUM_VIBRATO_RATE_HZ = 5.1
}
