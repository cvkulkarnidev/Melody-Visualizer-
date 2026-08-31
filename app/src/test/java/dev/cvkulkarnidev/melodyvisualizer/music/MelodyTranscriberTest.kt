package dev.cvkulkarnidev.melodyvisualizer.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class MelodyTranscriberTest {
    @Test
    fun `transcribes a completed three-note humming-style recording`() {
        val audio = concatenate(
            silence(120),
            tone(440.0, 460),
            silence(150),
            tone(523.25, 460),
            silence(150),
            tone(659.25, 500),
            silence(120),
        )

        val notes = MelodyTranscriber(sampleRate = SAMPLE_RATE).transcribe(audio)

        assertTrue("Expected at least three note events, got $notes", notes.size >= 3)
        assertEquals(listOf("A4", "C5", "E5"), notes.take(3).map { it.note.name })
        assertTrue(notes.take(3).all { it.durationMillis >= 250L })
    }

    @Test
    fun `returns no notes for silence`() {
        val notes = MelodyTranscriber(sampleRate = SAMPLE_RATE).transcribe(silence(1_200))

        assertTrue(notes.isEmpty())
    }

    private fun tone(frequencyHz: Double, durationMillis: Int): ShortArray {
        val sampleCount = durationMillis * SAMPLE_RATE / 1_000
        return ShortArray(sampleCount) { index ->
            val attack = (index / (SAMPLE_RATE * 0.03)).coerceIn(0.0, 1.0)
            val release = ((sampleCount - index) / (SAMPLE_RATE * 0.03)).coerceIn(0.0, 1.0)
            val envelope = minOf(attack, release)
            (sin(2.0 * PI * frequencyHz * index / SAMPLE_RATE) * Short.MAX_VALUE * 0.52 * envelope)
                .toInt()
                .toShort()
        }
    }

    private fun silence(durationMillis: Int): ShortArray =
        ShortArray(durationMillis * SAMPLE_RATE / 1_000)

    private fun concatenate(vararg arrays: ShortArray): ShortArray {
        val result = ShortArray(arrays.sumOf { it.size })
        var offset = 0
        arrays.forEach { array ->
            array.copyInto(result, offset)
            offset += array.size
        }
        return result
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
    }
}
