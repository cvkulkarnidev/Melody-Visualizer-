package dev.cvkulkarnidev.melodyvisualizer.music

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class OctaveValidatorTest {
    @Test
    fun `confirmed upper octave corrects lower octave visualization`() {
        val audio = sineWave(frequencyHz = 783.99, durationMillis = 700L)
        val incorrectlyLower = DetectedNoteEvent(
            note = MusicNote.fromMidi(67),
            startMillis = 0L,
            durationMillis = 700L,
            confidence = 0.82f,
        )

        val corrected = OctaveValidator.correct(
            notes = listOf(incorrectlyLower),
            samples = audio,
            sampleRate = SAMPLE_RATE,
            sourceAHz = 440.0,
        )

        assertEquals("G5", corrected.single().note.name)
    }

    @Test
    fun `correct lower octave is not changed`() {
        val audio = sineWave(frequencyHz = 392.0, durationMillis = 700L)
        val correct = DetectedNoteEvent(
            note = MusicNote.fromMidi(67),
            startMillis = 0L,
            durationMillis = 700L,
            confidence = 0.90f,
        )

        val validated = OctaveValidator.correct(
            notes = listOf(correct),
            samples = audio,
            sampleRate = SAMPLE_RATE,
            sourceAHz = 440.0,
        )

        assertEquals("G4", validated.single().note.name)
    }

    private fun sineWave(frequencyHz: Double, durationMillis: Long): ShortArray {
        val count = (SAMPLE_RATE * durationMillis / 1_000L).toInt()
        return ShortArray(count) { sample ->
            (sin(2.0 * PI * frequencyHz * sample / SAMPLE_RATE) * Short.MAX_VALUE * 0.55)
                .toInt()
                .toShort()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
    }
}
