package dev.cvkulkarnidev.melodyvisualizer.music

import dev.cvkulkarnidev.melodyvisualizer.audio.YinPitchDetector
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.roundToInt

/** Corrects only strongly confirmed one-octave F0 errors; all other detected notes are untouched. */
internal object OctaveValidator {
    fun correct(
        notes: List<DetectedNoteEvent>,
        samples: ShortArray,
        sampleRate: Int,
        sourceAHz: Double,
    ): List<DetectedNoteEvent> {
        if (notes.isEmpty() || samples.size < WINDOW_SIZE || sampleRate <= 0) return notes
        val detector = YinPitchDetector(
            sampleRate = sampleRate,
            minimumFrequencyHz = 55.0,
            maximumFrequencyHz = 1_200.0,
        )

        return notes.map { event ->
            if (event.durationMillis < MINIMUM_VALIDATION_MILLIS) return@map event
            val windowStarts = VALIDATION_POSITIONS.mapNotNullTo(linkedSetOf<Int>()) { position ->
                val centerMillis = event.startMillis + (event.durationMillis * position).toLong()
                val centerSample = centerMillis * sampleRate / 1_000L
                (centerSample - WINDOW_SIZE / 2L)
                    .coerceIn(0L, (samples.size - WINDOW_SIZE).toLong())
                    .toInt()
                    .takeIf { it + WINDOW_SIZE <= samples.size }
            }
            if (windowStarts.size < REQUIRED_VOTES) return@map event

            val votes = mutableMapOf<Int, MutableList<Float>>()
            windowStarts.forEach { start ->
                val frame = samples.copyOfRange(start, start + WINDOW_SIZE)
                val detection = detector.analyse(frame).detection ?: return@forEach
                if (detection.confidence < MINIMUM_YIN_CONFIDENCE) return@forEach
                val exactMidi = 69.0 + 12.0 * log2(detection.frequencyHz / sourceAHz)
                val candidateMidi = exactMidi.roundToInt().coerceIn(0, 127)
                if (abs(exactMidi - candidateMidi) > MAX_DISTANCE_FROM_NOTE) return@forEach
                if (abs(candidateMidi - event.note.midi) != 12) return@forEach
                votes.getOrPut(candidateMidi) { mutableListOf() } += detection.confidence
            }

            val confirmed = votes.entries
                .filter { it.value.size >= REQUIRED_VOTES }
                .maxByOrNull { it.value.average() }
                ?: return@map event
            event.copy(
                note = MusicNote.fromMidi(confirmed.key),
                confidence = ((event.confidence + confirmed.value.average().toFloat()) / 2f)
                    .coerceIn(0f, 1f),
            )
        }
    }

    private const val WINDOW_SIZE = 2_048
    private const val MINIMUM_VALIDATION_MILLIS = 120L
    private const val MINIMUM_YIN_CONFIDENCE = 0.88f
    private const val MAX_DISTANCE_FROM_NOTE = 0.34
    private const val REQUIRED_VOTES = 2
    private val VALIDATION_POSITIONS = doubleArrayOf(0.28, 0.50, 0.72)
}
