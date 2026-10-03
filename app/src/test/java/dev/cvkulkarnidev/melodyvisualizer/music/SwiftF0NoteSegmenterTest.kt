package dev.cvkulkarnidev.melodyvisualizer.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

class SwiftF0NoteSegmenterTest {
    @Test
    fun `vibrato is one A440 tuned note`() {
        val contour = contour(70) { frame ->
            val cents = 34.0 * sin(2.0 * PI * frame / 18.0)
            440.0 * 2.0.pow(cents / 1_200.0)
        }

        val notes = SwiftF0NoteSegmenter.segment(contour)

        assertEquals(1, notes.size)
        assertEquals("A4", notes.single().note.name)
        assertTrue(notes.single().durationMillis >= 1_000L)
        assertEquals(440.0, notes.single().note.frequencyHz, 0.001)
    }

    @Test
    fun `brief octave glitch does not become a false note`() {
        val contour = contour(80) { frame -> if (frame in 35..36) 880.0 else 440.0 }

        val notes = SwiftF0NoteSegmenter.segment(contour)

        assertEquals(listOf("A4"), notes.map { it.note.name }.distinct())
    }

    @Test
    fun `stable pitch change creates two tuned notes`() {
        val contour = contour(100) { frame -> if (frame < 50) 437.0 else 526.0 }

        val notes = SwiftF0NoteSegmenter.segment(contour)

        assertEquals(listOf("A4", "C5"), notes.map { it.note.name })
        assertTrue(notes.all { it.durationMillis >= 700L })
    }

    @Test
    fun `unvoiced quiet gap separates repeated notes`() {
        val frames = 90
        val pitch = DoubleArray(frames) { 440.0 }
        val confidence = FloatArray(frames) { frame -> if (frame in 38..48) 0.05f else 0.96f }
        val loudness = DoubleArray(frames) { frame -> if (frame in 38..48) -70.0 else -14.0 }

        val notes = SwiftF0NoteSegmenter.segment(PitchContour(pitch, confidence, loudness, 16.0))

        assertEquals(2, notes.size)
        assertTrue(notes.all { it.note.name == "A4" })
    }

    private fun contour(frames: Int, pitchAt: (Int) -> Double) = PitchContour(
        pitchHz = DoubleArray(frames, pitchAt),
        confidence = FloatArray(frames) { 0.96f },
        loudnessDb = DoubleArray(frames) { -14.0 },
        framePeriodMillis = 16.0,
    )
}
