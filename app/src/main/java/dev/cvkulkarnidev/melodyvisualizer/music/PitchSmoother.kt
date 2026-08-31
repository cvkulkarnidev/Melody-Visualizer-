package dev.cvkulkarnidev.melodyvisualizer.music

import dev.cvkulkarnidev.melodyvisualizer.audio.PitchDetection
import kotlin.math.roundToInt

data class SmoothedPitch(
    val note: MusicNote,
    val cents: Int,
    val frequencyHz: Double,
    val confidence: Float,
)

class PitchSmoother(
    private val windowSize: Int = 5,
    private val minimumConfidence: Float = 0.70f,
) {
    private val midiWindow = ArrayDeque<Double>()
    private var currentMidi: Int? = null
    private var invalidFrames = 0

    fun update(detection: PitchDetection?): SmoothedPitch? {
        if (detection == null || detection.confidence < minimumConfidence) {
            invalidFrames++
            if (invalidFrames >= 4) {
                midiWindow.clear()
                currentMidi = null
            }
            return currentMidi?.let { midi ->
                SmoothedPitch(MusicNote.fromMidi(midi), 0, MusicNote.fromMidi(midi).frequencyHz, 0f)
            }
        }

        invalidFrames = 0
        midiWindow.addLast(MusicNote.frequencyToMidi(detection.frequencyHz))
        while (midiWindow.size > windowSize) midiWindow.removeFirst()

        val sorted = midiWindow.sorted()
        val medianMidi = sorted[sorted.size / 2]
        val previous = currentMidi
        currentMidi = when {
            previous == null -> medianMidi.roundToInt()
            medianMidi > previous + NOTE_CHANGE_HYSTERESIS -> medianMidi.roundToInt()
            medianMidi < previous - NOTE_CHANGE_HYSTERESIS -> medianMidi.roundToInt()
            else -> previous
        }.coerceIn(0, 127)

        val note = MusicNote.fromMidi(currentMidi!!)
        val cents = ((medianMidi - note.midi) * 100.0).roundToInt().coerceIn(-50, 50)
        return SmoothedPitch(
            note = note,
            cents = cents,
            frequencyHz = detection.frequencyHz,
            confidence = detection.confidence,
        )
    }

    fun reset() {
        midiWindow.clear()
        currentMidi = null
        invalidFrames = 0
    }

    private companion object {
        const val NOTE_CHANGE_HYSTERESIS = 0.62
    }
}
