package dev.cvkulkarnidev.melodyvisualizer.music

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

data class MusicNote(
    val midi: Int,
    val name: String,
    val pitchClass: String,
    val octave: Int,
    val frequencyHz: Double,
) {
    val isBlackKey: Boolean
        get() = midi.mod(12) in BLACK_KEY_PITCH_CLASSES

    companion object {
        private val NOTE_NAMES = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
        private val BLACK_KEY_PITCH_CLASSES = setOf(1, 3, 6, 8, 10)

        fun fromFrequency(frequencyHz: Double): MusicNote {
            val midi = frequencyToMidi(frequencyHz).roundToInt().coerceIn(0, 127)
            return fromMidi(midi)
        }

        fun fromMidi(midi: Int): MusicNote {
            val safeMidi = midi.coerceIn(0, 127)
            val pitchClass = NOTE_NAMES[safeMidi.mod(12)]
            val octave = safeMidi / 12 - 1
            return MusicNote(
                midi = safeMidi,
                name = "$pitchClass$octave",
                pitchClass = pitchClass,
                octave = octave,
                frequencyHz = 440.0 * 2.0.pow((safeMidi - 69) / 12.0),
            )
        }

        fun frequencyToMidi(frequencyHz: Double): Double =
            69.0 + 12.0 * (ln(frequencyHz / 440.0) / ln(2.0))
    }
}
